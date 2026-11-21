package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.net.S2CRollbackPreviewPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Splits rollback into two safe parts: ClickHouse paging on a worker thread and bounded world
 * mutations on server ticks. Only one world-changing rollback can run at once.
 */
public final class RollbackCoordinator {
    public enum CancelResult { NONE, PREPARATION, ACTIVE }

    private record PreparedScope(String dimension, RollbackEngine.PreparedRollback rollback) {}

    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
    private static final ConcurrentHashMap<UUID, Preparation> PREPARATIONS = new ConcurrentHashMap<>();
    private static volatile ThreadPoolExecutor executor;
    private static volatile ActiveJob active;
    private static final TicketType<Long> ROLLBACK_CHUNK = TicketType.create("avilixlogger_rollback", Long::compare, 220);
    // Only one prepared preview is retained across confirmations to keep the global memory bounded.
    private static CachedPreview cachedPreview;
    private record CachedPreview(RollbackPlanManager.Plan plan, List<PreparedScope> scopes) {}
    public record IssuePage(int total,List<EntityReplayPlan.Issue> issues) {}

    /** Reuses the one bounded preview; no NBT or new history request is retained for paging. */
    public static IssuePage previewIssues(UUID owner,RollbackPlanManager.Plan plan,int page) {
        var cached=cachedPreview;
        if(cached==null||cached.plan()!=plan||!plan.playerId().equals(owner))return null;
        int total=0;for(var scope:cached.scopes())total+=scope.rollback().preparationIssues().size();
        long start=(long)(page-1)*12;var rows=new ArrayList<EntityReplayPlan.Issue>();long seen=0;
        for(var scope:cached.scopes())for(var issue:scope.rollback().preparationIssues()) {
            if(seen++>=start&&rows.size()<12)rows.add(issue);
        }
        return new IssuePage(total,List.copyOf(rows));
    }

    private RollbackCoordinator() {}

    public static void preparePreview(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                      Consumer<RollbackReport> onComplete, Consumer<String> onError) {
        submitPreparation(server, playerId, plan, false, onComplete, null, onError, null);
    }

    public static void startRollback(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                     Runnable onStarted, Consumer<RollbackReport> onComplete,
                                     Consumer<String> onError) {
        startRollback(server,playerId,plan,onStarted,onComplete,onError,null);
    }

    public static void startRollback(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                     Runnable onStarted, Consumer<RollbackReport> onComplete,
                                     Consumer<String> onError, Consumer<String> onProgress) {
        if (!LoggerConfig.isEnabled()) {
            onError.accept("Logger отключён. Откат недоступен.");
            return;
        }
        if (active != null || CartAuditContext.rollbackActive) {
            onError.accept("Другой откат уже изменяет мир. Дождитесь его завершения.");
            return;
        }
        CachedPreview cached = cachedPreview;
        if (cached != null && cached.plan() == plan) {
            cachedPreview = null;
            active = new ActiveJob(playerId, plan, cached.scopes(), onComplete, onError).progress(onProgress);
            if (onStarted != null) onStarted.run();
            return;
        }
        submitPreparation(server, playerId, plan, true, onComplete, onStarted, onError, onProgress);
    }

    private static void submitPreparation(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                          boolean apply, Consumer<RollbackReport> onComplete,
                                          Runnable onStarted, Consumer<String> onError, Consumer<String> onProgress) {
        if (!LoggerConfig.isEnabled()) {
            onError.accept("AvilixAuthCore не разрешил запуск Logger. Откат отключён.");
            return;
        }
        if (server == null || playerId == null || plan == null) {
            onError.accept("Не удалось подготовить откат: сервер или план недоступен.");
            return;
        }
        if (cachedPreview != null) cachedPreview = null;
        Preparation next = new Preparation();
        Preparation previous = PREPARATIONS.put(playerId, next);
        if (previous != null) previous.cancelled = true;

        LogStorage storage = LoggerRuntime.storage(server.overworld());
        try {
            executor().execute(() -> {
                try {
                    if (!storage.awaitVisible(System.nanoTime() + TimeUnit.SECONDS.toNanos(30)))
                        throw new IllegalStateException("Журнал ещё не сохранён полностью. Повторите предпросмотр после записи очереди.");
                    List<PreparedScope> scopes = new ArrayList<>(plan.scopes().size());
                    RollbackReport preview = new RollbackReport();
                    boolean collectMarkers = !apply && plan.previewBounds() != null;
                    long totalPreparedBytes = 0L;
                    long maxPreparedBytes = configInt(() -> LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get(),
                            512, 64, 8192) * 1024L * 1024L;
                    for (RollbackPlanManager.Scope scope : plan.scopes()) {
                        if (next.cancelled || PREPARATIONS.get(playerId) != next) return;
                        java.util.function.BooleanSupplier cancelled = () -> next.cancelled || Thread.currentThread().isInterrupted();
                        RollbackEngine.PreparedRollback prepared = plan.replay()
                                ? RollbackEngine.prepareBlockReplay(storage, plan, scope, cancelled)
                                : RollbackEngine.prepareBoxRange(storage, scope.dimension(), scope.min(), scope.max(),
                                        plan.targetTs(), plan.cutoffTs(), plan.actor(), plan.types(), cancelled);
                        scopes.add(new PreparedScope(scope.dimension(), prepared));
                        totalPreparedBytes += prepared.estimatedBytes();
                        if (totalPreparedBytes > maxPreparedBytes) {
                            throw new IllegalStateException("план превышает "
                                    + (maxPreparedBytes / (1024L * 1024L)) + " MiB; сузьте область или период");
                        }
                        if (!apply) {
                            if (collectMarkers) collectBlockMarkers(prepared, preview);
                            preview.merge(RollbackEngine.previewPrepared(prepared, collectMarkers ? entry -> addEntityMarker(preview, entry) : null));
                        }
                    }
                    if (collectMarkers) preview.previewMarkers = List.copyOf(preview.previewMarkers);
                    server.execute(() -> {
                        if (next.cancelled || !PREPARATIONS.remove(playerId, next)) return;
                        if (!apply) {
                            if (RollbackPlanManager.getValid(playerId) != plan) return;
                            if (plan.replay() && preview.skipped == 0) {
                                if (active != null || CartAuditContext.rollbackActive) {
                                    onError.accept("Другая операция проверяет или изменяет мир. Повторите предпросмотр после её завершения.");
                                    return;
                                }
                                active = new ActiveJob(playerId, plan, scopes, checked -> {
                                    if (RollbackPlanManager.getValid(playerId) != plan) {
                                        onError.accept("Параметры предпросмотра изменились или срок плана истёк. Создайте его заново.");
                                        return;
                                    }
                                    cachedPreview = new CachedPreview(plan, scopes);
                                    checked.previewMarkers = preview.previewMarkers;
                                    checked.previewLimited = preview.previewLimited;
                                    checked.preparationIssues = preview.preparationIssues;
                                    onComplete.accept(checked);
                                }, onError);
                                active.preflightOnly = true;
                                return;
                            }
                            cachedPreview = new CachedPreview(plan, scopes);
                            onComplete.accept(preview);
                            return;
                        }
                        if (active != null || CartAuditContext.rollbackActive) {
                            onError.accept("Другой откат уже изменяет мир. Подготовленный план не применён.");
                            return;
                        }
                        active = new ActiveJob(playerId, plan, scopes, onComplete, onError).progress(onProgress);
                        if (onStarted != null) onStarted.run();
                    });
                } catch (Throwable t) {
                    if (next.cancelled || Thread.currentThread().isInterrupted()) return;
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Rollback preparation failed", t);
                    server.execute(() -> {
                        if (PREPARATIONS.remove(playerId, next) && !next.cancelled) {
                            onError.accept("Подготовка отката завершилась ошибкой: " + concise(t));
                        }
                    });
                }
            });
        } catch (RejectedExecutionException rejected) {
            PREPARATIONS.remove(playerId, next);
            onError.accept("Очередь подготовки откатов занята. Повторите команду позже.");
        }
    }

    private static void collectBlockMarkers(RollbackEngine.PreparedRollback prepared, RollbackReport preview) {
        var markers = new ArrayList<S2CRollbackPreviewPayload.Marker>(S2CRollbackPreviewPayload.MAX_MARKERS);
        markers.addAll(preview.previewMarkers);
        int limit = S2CRollbackPreviewPayload.MAX_MARKERS;
        int blockLimit = prepared.deferred().isEmpty() ? limit : Math.max(markers.size(), limit - 64);
        for (var block : prepared.blocks()) {
            if (markers.size() == blockLimit) {
                preview.previewLimited = true;
                break;
            }
            if (block.blockBeforeSnbt() != null && block.stateTag() == null) continue;
            String id = block.stateTag() == null ? "" : block.stateTag().getString("Name");
            var kind = block.containerSlotsBeforeSnbt() != null || block.beBeforeSnbt() != null
                    ? S2CRollbackPreviewPayload.Kind.CONTAINER : id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                    || id.equals("minecraft:void_air") ? S2CRollbackPreviewPayload.Kind.REMOVE : S2CRollbackPreviewPayload.Kind.BLOCK;
            markers.add(new S2CRollbackPreviewPayload.Marker(block.pos(), kind, id.length() <= 256 ? id : ""));
        }
        preview.previewMarkers = markers;
    }

    private static void addEntityMarker(RollbackReport preview, LogEntry row) {
        if (row.entityType.length() > 256) return;
        if (preview.previewMarkers.size() == S2CRollbackPreviewPayload.MAX_MARKERS) {
            preview.previewLimited = true;
            return;
        }
        preview.previewMarkers.add(new S2CRollbackPreviewPayload.Marker(new BlockPos(row.x, row.y, row.z),
                S2CRollbackPreviewPayload.Kind.ENTITY, row.entityType));
    }

    public static CancelResult cancel(UUID playerId) {
        if (playerId == null) return CancelResult.NONE;
        if (cachedPreview != null && playerId.equals(cachedPreview.plan().playerId())) cachedPreview = null;
        Preparation preparing = PREPARATIONS.remove(playerId);
        if (preparing != null) {
            preparing.cancelled = true;
            return CancelResult.PREPARATION;
        }
        ActiveJob job = active;
        if (job != null && playerId.equals(job.playerId)) {
            if (job.plan.replay() && job.scene != null) {
                job.scene.cancel("Восстановление отменено администратором; применённые изменения возвращаются.");
                return CancelResult.ACTIVE;
            }
            job.releaseChunk();
            active = null;
            job.finishCheckpoint();
            return CancelResult.ACTIVE;
        }
        return CancelResult.NONE;
    }

    public static boolean hasActiveRollback() {
        return active != null;
    }

    public static void cancelPreparation(UUID playerId) {
        if (playerId == null) return;
        Preparation preparation = PREPARATIONS.remove(playerId);
        if (preparation != null) preparation.cancelled = true;
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (cachedPreview != null && (!LoggerConfig.isEnabled()
                || RollbackPlanManager.getValid(cachedPreview.plan().playerId()) != cachedPreview.plan())) {
            cachedPreview = null;
        }
        ActiveJob job = active;
        if (job == null && PREPARATIONS.isEmpty()) return;
        if (!LoggerConfig.isEnabled()) {
            if (active != null || !PREPARATIONS.isEmpty()) shutdown();
            return;
        }
        if (job == null) return;
        // hasTime() can stay false indefinitely on a busy server. World changes remain
        // bounded by our own operation/time budgets and must make progress every tick.
        try {
            if (job.tick(event.getServer())) {
                job.releaseChunk();
                active = null;
            } else job.reportDelay(event.getServer());
        } catch (Throwable t) {
            if (job.plan.replay() && job.scene != null) {
                if (job.scene.quarantined()) {
                    if (!job.sceneFailureReported) { job.sceneFailureReported=true; job.onError.accept(job.scene.failure()); }
                } else job.scene.cancel("Ошибка восстановления: " + concise(t));
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] Recovery scene failed; compensating or retaining locks",t);
                return;
            }
            job.releaseChunk();
            active = null;
            try { job.finishCheckpoint(); }
            catch (Throwable saveFailure) { t.addSuppressed(saveFailure); }
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Tick-budgeted rollback failed", t);
            job.onError.accept("Откат остановлен из-за ошибки: " + concise(t));
        }
    }

    public static void shutdown() {
        RollbackPlanManager.clearAll();
        cachedPreview = null;
        for (Preparation preparation : PREPARATIONS.values()) preparation.cancelled = true;
        PREPARATIONS.clear();
        if (active != null) {
            if (active.plan.replay() && active.scene != null) active.compensateBeforeStop();
            else try {active.finishCheckpoint();}catch(Throwable failure){AvilixLoggerMod.LOGGER.error("[AvilixLogger] Rollback shutdown checkpoint failed",failure);}
            active.releaseChunk();
        }
        active = null;
        ThreadPoolExecutor ex = executor;
        executor = null;
        if (ex != null) ex.shutdownNow();
    }

    public static boolean submitEntryLookup(Runnable task) {
        try {
            executor().execute(task);
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor current = executor;
        if (current != null) return current;
        synchronized (RollbackCoordinator.class) {
            current = executor;
            if (current != null) return current;
            ThreadFactory factory = task -> {
                Thread thread = new Thread(task, "avilixlogger-rollback-prepare-" + THREAD_ID.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return thread;
            };
            current = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(4), factory, new ThreadPoolExecutor.AbortPolicy());
            current.allowCoreThreadTimeOut(true);
            executor = current;
            return current;
        }
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceLocation id = ResourceLocation.tryParse(dimension);
            if (id == null) return null;
            return server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String concise(Throwable t) {
        String message = t == null ? null : t.getMessage();
        return message == null || message.isBlank() ? (t == null ? "unknown" : t.getClass().getSimpleName()) : message;
    }

    private static int configInt(java.util.function.IntSupplier supplier, int fallback, int min, int max) {
        try { return Math.max(min, Math.min(max, supplier.getAsInt())); }
        catch (Throwable ignored) { return fallback; }
    }

    private static final class Preparation {
        volatile boolean cancelled;
    }

    private static final class ActiveJob {
        final UUID playerId;
        final RollbackPlanManager.Plan plan;
        final List<PreparedScope> scopes;
        final Consumer<RollbackReport> onComplete;
        final Consumer<String> onError;
        final RollbackReport report = new RollbackReport();
        int scopeIndex;
        int blockIndex;
        int removalIndex;
        int deferredIndex;
        ServerLevel ticketLevel;
        ChunkPos ticketChunk;
        int ticketTick;
        ServerLevel timedOutLevel;
        int timedOutX, timedOutZ;
        int lastProgressTick = Integer.MIN_VALUE;
        int lastReportedProcessed;
        MinecraftServer checkpointServer;
        boolean checkpointStarted;
        boolean checkpointFinished;
        RecoveryScene scene;
        boolean sceneFailureReported;
        Consumer<String> progressSink;
        RecoveryScene.Phase lastReportedPhase;
        boolean preflightOnly;

        ActiveJob progress(Consumer<String> sink) { progressSink=sink; return this; }

        void compensateBeforeStop() {
            try {scene.cancel("Сервер завершает работу; незавершённая сцена возвращается к исходному состоянию.");}
            catch(Throwable failure){AvilixLoggerMod.LOGGER.error("[AvilixLogger] Recovery cancellation during shutdown failed; journal retained",failure);}
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while (!scene.aborted() && !scene.quarantined() && scene.phase()!=RecoveryScene.Phase.CLOSED && System.nanoTime()<deadline) {
                try { scene.step(10000,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),64); }
                catch (Throwable failure) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Recovery compensation during shutdown failed",failure); break; }
                if(scene.phase()==RecoveryScene.Phase.WRITE_JOURNAL)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            if(!scene.aborted()&&scene.phase()!=RecoveryScene.Phase.CLOSED)
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] Recovery could not finish compensation during shutdown; durable journal retained for next startup. phase={}",scene.phase());
            try { finishCheckpoint(); }
            catch (Throwable failure) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Recovery checkpoint during shutdown failed",failure); }
        }

        boolean tickRecovery(MinecraftServer server) {
            if (scene == null) {
                var targets=new ArrayList<RecoveryScene.Target>();
                var entityTargets=new ArrayList<RecoveryEntityBatch.Target>();
                long preparedBytes=0;
                for (var scope:scopes) {
                    if (!scope.rollback().preparationSkips().isEmpty()) throw new IllegalStateException(
                            "Полное восстановление не начато: история содержит пропуски " + scope.rollback().preparationSkips());
                    var level=resolveLevel(server,scope.dimension());
                    if (level==null) throw new IllegalStateException("Измерение недоступно: "+scope.dimension());
                    for (var block:scope.rollback().blocks()) targets.add(new RecoveryScene.Target(level,block));
                    for(var entity:scope.rollback().entityReplays())entityTargets.add(new RecoveryEntityBatch.Target(level,entity));
                    preparedBytes+=scope.rollback().estimatedBytes();
                }
                long limit=configInt(() -> LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get(),512,64,8192)*1024L*1024L;
                var storage=entityTargets.isEmpty()?null:LoggerRuntime.storage(server.overworld());
                scene=new RecoveryScene(targets,entityTargets,Math.max(0,limit-preparedBytes),
                        configInt(() -> LoggerConfig.VALUES.recoveryMaxLoadedChunks.get(),128,1,4096),entities->EntityReplayPlan.verifyHistory(storage,entities));
                if (preflightOnly) scene.preflightOnly();
            }
            if (scene.quarantined()) return false;
            int operations=configInt(() -> LoggerConfig.VALUES.recoveryOperationsPerTick.get(),2048,1,10000);
            int millis=configInt(() -> LoggerConfig.VALUES.recoveryMaxMillisPerTick.get(),20,1,100);
            int loads=configInt(() -> LoggerConfig.VALUES.recoveryChunkLoadsPerTick.get(),8,0,64);
            scene.step(operations,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(millis),loads);
            if (scene.ready()) {
                if (preflightOnly) {
                    report.merge(scene.completePreflight());
                    report.livePreviewVerified = true;
                    checkpointFinished = true;
                    onComplete.accept(report);
                    return true;
                }
                scene.commit();
                checkpointFinished=true;
                report.merge(scene.report());
                onComplete.accept(report);
                return true;
            }
            if (scene.aborted()) {
                checkpointFinished=true; // Scene flushed its whole compensation before retiring the undo journal.
                onError.accept(scene.failure() + (preflightOnly ? " Предпросмотр остановлен; мир не изменён."
                        : " Постройка возвращена к исходному состоянию; частичный результат не оставлен."));
                return true;
            }
            return false;
        }

        void finishCheckpoint() {
            if (!checkpointStarted || checkpointFinished) return;
            RecoveryCheckpoint.flush(checkpointServer);
            checkpointFinished = true;
        }

        void releaseChunk() {
            if (ticketLevel != null) ticketLevel.getChunkSource().removeRegionTicket(ROLLBACK_CHUNK, ticketChunk, 1, ticketChunk.toLong());
            ticketLevel = null;
            ticketChunk = null;
        }

        private boolean waitForChunk(ServerLevel level, BlockPos pos) {
            int x = pos.getX() >> 4, z = pos.getZ() >> 4;
            if (timedOutLevel == level && timedOutX == x && timedOutZ == z) return false;
            int tick = level.getServer().getTickCount();
            if (ticketLevel == level && ticketChunk.x == x && ticketChunk.z == z) {
                if (tick - ticketTick < 200) return true;
                timedOutLevel = level;
                timedOutX = x;
                timedOutZ = z;
                releaseChunk();
                return false;
            }
            releaseChunk();
            ticketLevel = level;
            ticketChunk = new ChunkPos(x, z);
            ticketTick = tick;
            level.getChunkSource().addRegionTicket(ROLLBACK_CHUNK, ticketChunk, 1, ticketChunk.toLong());
            return true;
        }

        void reportDelay(MinecraftServer server) {
            int tick = server.getTickCount();
            if (lastProgressTick == Integer.MIN_VALUE) {
                lastProgressTick = tick;
                return;
            }
            boolean phaseChanged=scene!=null && scene.phase()!=lastReportedPhase;
            if (tick - lastProgressTick < (phaseChanged ? 5 : 20)) return;
            lastProgressTick = tick;
            if (scene!=null) lastReportedPhase=scene.phase();
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !PermissionUtil.has(player.createCommandSourceStack(), "avilixlogger.command.admin", 2)) return;
            String state = scene != null ? recoveryPhase(scene.phase()) + " · " + scene.progress() + "/" + scene.progressTotal()
                    : report.processed != lastReportedProcessed ? "Выполняется"
                    : ticketChunk == null ? "Подготовка следующей порции" : "Загрузка чанка " + ticketChunk;
            lastReportedProcessed = report.processed;
            String message=state+(scene==null?" · обработано "+report.processed:"");
            if (progressSink!=null) progressSink.accept(message);
            else player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }


        private static String recoveryPhase(RecoveryScene.Phase phase) {
            return switch (phase) {
                case RESERVE -> "Защита области";
                case ENTITY_INDEX -> "Проверка UUID в сохранённых чанках";
                case ENTITY_CAPTURE, ENTITY_RECHECK -> "Проверка сущностей и связанных снимков";
                case ENTITY_APPLY -> "Восстановление сущностей";
                case ENTITY_VERIFY -> "Проверка состояния сущностей";
                case WAIT_CHUNKS -> "Загрузка чанков";
                case CAPTURE -> "Резервные снимки всей постройки";
                case RECHECK -> "Проверка связанных блоков";
                case WRITE_JOURNAL -> "Сохранение аварийного журнала операции";
                case STATES -> "Установка всех состояний блоков";
                case DATA -> "Восстановление NBT и хранилищ";
                case VERIFY, READY -> "Проверка результата перед сохранением";
                case UNDO_STATES, UNDO_DATA, UNDO_ENTITIES -> "Компенсация: возврат всей постройки";
                case QUARANTINED -> "Операция остановлена: область защищена";
                case ABORTED -> "Восстановление отменено";
                case CLOSED -> "Готово";
            };
        }

        ActiveJob(UUID playerId, RollbackPlanManager.Plan plan, List<PreparedScope> scopes,
                  Consumer<RollbackReport> onComplete, Consumer<String> onError) {
            this.playerId = playerId;
            this.plan = plan;
            this.scopes = List.copyOf(scopes);
            this.onComplete = onComplete;
            this.onError = onError;
            for (var scope : scopes) scope.rollback().preparationSkips().forEach((reason, count) -> {
                report.skippedReasons.merge(reason, count, Integer::sum);
                report.skipped += count;
                report.processed += count;
            });
        }

        /** @return true when the job has finished and can be removed. */
        boolean tick(MinecraftServer server) {
            if (!checkpointStarted) {
                checkpointServer = server;
                if (!preflightOnly) RecoveryCheckpoint.flush(server);
                checkpointStarted = true;
                // Saving may consume a tick; start applying with a fresh budget next tick.
                return false;
            }
            if (plan.replay()) return tickRecovery(server);
            int operationBudget = configInt(() -> LoggerConfig.VALUES.rollbackOperationsPerTick.get(), 100, 1, 10_000);
            int blockBatchSize = configInt(() -> LoggerConfig.VALUES.rollbackBlockBatchSize.get(), 32, 1, 1_000);
            int chunkLoadBudget = configInt(() -> LoggerConfig.VALUES.rollbackChunkLoadsPerTick.get(), 1, 0, 64);
            int millis = configInt(() -> LoggerConfig.VALUES.rollbackMaxMillisPerTick.get(), 2, 1, 25);
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
            int used = 0;

            while (scopeIndex < scopes.size() && used < operationBudget && System.nanoTime() < deadline) {
                PreparedScope scope = scopes.get(scopeIndex);
                ServerLevel level = resolveLevel(server, scope.dimension());
                if (level == null) {
                    finishCheckpoint();
                    onError.accept("Измерение из плана больше недоступно: " + scope.dimension());
                    return true;
                }
                RollbackEngine.PreparedRollback prepared = scope.rollback();

                // Remove spawned contraptions/entities before restoring the stationary blocks.
                if (removalIndex >= prepared.entityRemovals().size() && blockIndex < prepared.blocks().size()) {
                    RollbackEngine.PreparedBlockSnapshot first = prepared.blocks().get(blockIndex);
                    if (level.getChunkSource().getChunkNow(first.pos().getX() >> 4, first.pos().getZ() >> 4) == null) {
                        if (chunkLoadBudget > 0 && waitForChunk(level, first.pos())) return false;
                        report.onSkipped(chunkLoadBudget == 0 ? "chunk_unloaded" : "chunk_timeout");
                        blockIndex++;
                        used++;
                        continue;
                    }
                    int chunkX = first.pos().getX() >> 4;
                    int chunkZ = first.pos().getZ() >> 4;
                    if (ticketLevel != null && (ticketLevel != level || ticketChunk.x != chunkX || ticketChunk.z != chunkZ)) releaseChunk();
                    int count = Math.min(blockBatchSize, operationBudget - used);
                    ArrayList<RollbackEngine.PreparedBlockSnapshot> batch = new ArrayList<>(count);
                    while (blockIndex + batch.size() < prepared.blocks().size() && batch.size() < count) {
                        RollbackEngine.PreparedBlockSnapshot candidate = prepared.blocks().get(blockIndex + batch.size());
                        if ((candidate.pos().getX() >> 4) != chunkX || (candidate.pos().getZ() >> 4) != chunkZ) break;
                        batch.add(candidate);
                    }
                    report.merge(RollbackEngine.applyPreparedBlockBatch(level, batch));
                    blockIndex += batch.size();
                    used += batch.size();
                    continue;
                }

                if (removalIndex < prepared.entityRemovals().size()) {
                    LogEntry entry = prepared.entityRemovals().get(removalIndex);
                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                    if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                        if (chunkLoadBudget > 0 && waitForChunk(level, pos)) return false;
                        report.onSkipped(chunkLoadBudget == 0 ? "chunk_unloaded" : "chunk_timeout");
                        removalIndex++;
                        used++;
                        continue;
                    }
                    report.merge(RollbackEngine.applyPreparedEntry(level, entry));
                    removalIndex++;
                    used++;
                    continue;
                }

                if (deferredIndex < prepared.deferred().size()) {
                    LogEntry entry = prepared.deferred().get(deferredIndex);
                    BlockPos pos = new BlockPos(entry.x, entry.y, entry.z);
                    if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
                        if (chunkLoadBudget > 0 && waitForChunk(level, pos)) return false;
                        report.onSkipped(chunkLoadBudget == 0 ? "chunk_unloaded" : "chunk_timeout");
                        deferredIndex++;
                        used++;
                        continue;
                    }
                    report.merge(RollbackEngine.applyPreparedEntry(level, entry));
                    deferredIndex++;
                    used++;
                    continue;
                }

                scopeIndex++;
                blockIndex = 0;
                removalIndex = 0;
                deferredIndex = 0;
            }

            if (scopeIndex >= scopes.size()) {
                finishCheckpoint();
                onComplete.accept(report);
                return true;
            }
            return false;
        }

    }
}
