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

    private RollbackCoordinator() {}

    public static void preparePreview(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                      Consumer<RollbackReport> onComplete, Consumer<String> onError) {
        submitPreparation(server, playerId, plan, false, onComplete, null, onError);
    }

    public static void startRollback(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                     Runnable onStarted, Consumer<RollbackReport> onComplete,
                                     Consumer<String> onError) {
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
            active = new ActiveJob(playerId, plan, cached.scopes(), onComplete, onError);
            if (onStarted != null) onStarted.run();
            return;
        }
        submitPreparation(server, playerId, plan, true, onComplete, onStarted, onError);
    }

    private static void submitPreparation(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                          boolean apply, Consumer<RollbackReport> onComplete,
                                          Runnable onStarted, Consumer<String> onError) {
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
                            cachedPreview = new CachedPreview(plan, scopes);
                            onComplete.accept(preview);
                            return;
                        }
                        if (active != null || CartAuditContext.rollbackActive) {
                            onError.accept("Другой откат уже изменяет мир. Подготовленный план не применён.");
                            return;
                        }
                        active = new ActiveJob(playerId, plan, scopes, onComplete, onError);
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
            job.releaseChunk();
            active = null;
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
        if (!event.hasTime()) {
            job.reportDelay(event.getServer());
            return;
        }
        try {
            if (job.tick(event.getServer())) {
                job.releaseChunk();
                active = null;
            } else job.reportDelay(event.getServer());
        } catch (Throwable t) {
            job.releaseChunk();
            active = null;
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Tick-budgeted rollback failed", t);
            job.onError.accept("Откат остановлен из-за ошибки: " + concise(t));
        }
    }

    public static void shutdown() {
        RollbackPlanManager.clearAll();
        cachedPreview = null;
        for (Preparation preparation : PREPARATIONS.values()) preparation.cancelled = true;
        PREPARATIONS.clear();
        if (active != null) active.releaseChunk();
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
            if (tick - lastProgressTick < 200) return;
            lastProgressTick = tick;
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null || !PermissionUtil.has(player.createCommandSourceStack(), "avilixlogger.command.admin", 2)) return;
            String state = report.processed != lastReportedProcessed ? "Выполняется"
                    : ticketChunk == null ? "Ожидание свободного времени тика" : "Ожидание чанка " + ticketChunk;
            lastReportedProcessed = report.processed;
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(state + " · обработано " + report.processed));
        }


        ActiveJob(UUID playerId, RollbackPlanManager.Plan plan, List<PreparedScope> scopes,
                  Consumer<RollbackReport> onComplete, Consumer<String> onError) {
            this.playerId = playerId;
            this.plan = plan;
            this.scopes = List.copyOf(scopes);
            this.onComplete = onComplete;
            this.onError = onError;
        }

        /** @return true when the job has finished and can be removed. */
        boolean tick(MinecraftServer server) {
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
                    onError.accept("Измерение из плана больше недоступно: " + scope.dimension());
                    return true;
                }
                RollbackEngine.PreparedRollback prepared = scope.rollback();

                if (blockIndex < prepared.blocks().size()) {
                    RollbackEngine.PreparedBlockSnapshot first = prepared.blocks().get(blockIndex);
                    if (!level.hasChunkAt(first.pos())) {
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
                    if (!level.hasChunkAt(pos)) {
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
                    if (!level.hasChunkAt(pos)) {
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
                onComplete.accept(report);
                return true;
            }
            return false;
        }

    }
}
