package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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

    private RollbackCoordinator() {}

    public static void preparePreview(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                      Consumer<RollbackReport> onComplete, Consumer<String> onError) {
        submitPreparation(server, playerId, plan, false, onComplete, null, onError);
    }

    public static void startRollback(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                     Runnable onStarted, Consumer<RollbackReport> onComplete,
                                     Consumer<String> onError) {
        if (active != null) {
            onError.accept("Другой откат уже изменяет мир. Дождитесь его завершения.");
            return;
        }
        submitPreparation(server, playerId, plan, true, onComplete, onStarted, onError);
    }

    private static void submitPreparation(MinecraftServer server, UUID playerId, RollbackPlanManager.Plan plan,
                                          boolean apply, Consumer<RollbackReport> onComplete,
                                          Runnable onStarted, Consumer<String> onError) {
        if (server == null || playerId == null || plan == null) {
            onError.accept("Не удалось подготовить откат: сервер или план недоступен.");
            return;
        }
        Preparation next = new Preparation();
        Preparation previous = PREPARATIONS.put(playerId, next);
        if (previous != null) previous.cancelled = true;

        LogStorage storage = LoggerRuntime.storage(server.overworld());
        try {
            executor().execute(() -> {
                try {
                    List<PreparedScope> scopes = new ArrayList<>(plan.scopes().size());
                    RollbackReport preview = new RollbackReport();
                    long totalPreparedBytes = 0L;
                    long maxPreparedBytes = configInt(() -> LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get(),
                            512, 64, 8192) * 1024L * 1024L;
                    for (RollbackPlanManager.Scope scope : plan.scopes()) {
                        if (next.cancelled || PREPARATIONS.get(playerId) != next) return;
                        RollbackEngine.PreparedRollback prepared = RollbackEngine.prepareBoxRange(
                                storage, scope.dimension(), scope.min(), scope.max(),
                                plan.targetTs(), plan.cutoffTs(), plan.actor(), plan.types());
                        scopes.add(new PreparedScope(scope.dimension(), prepared));
                        totalPreparedBytes += prepared.estimatedBytes();
                        if (totalPreparedBytes > maxPreparedBytes) {
                            throw new IllegalStateException("план превышает "
                                    + (maxPreparedBytes / (1024L * 1024L)) + " MiB; сузьте область или период");
                        }
                        preview.merge(RollbackEngine.previewPrepared(prepared));
                    }
                    server.execute(() -> {
                        if (next.cancelled || !PREPARATIONS.remove(playerId, next)) return;
                        if (!apply) {
                            onComplete.accept(preview);
                            return;
                        }
                        if (active != null) {
                            onError.accept("Другой откат уже изменяет мир. Подготовленный план не применён.");
                            return;
                        }
                        active = new ActiveJob(playerId, plan, scopes, onComplete, onError);
                        if (onStarted != null) onStarted.run();
                    });
                } catch (Throwable t) {
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

    public static CancelResult cancel(UUID playerId) {
        if (playerId == null) return CancelResult.NONE;
        Preparation preparing = PREPARATIONS.remove(playerId);
        if (preparing != null) {
            preparing.cancelled = true;
            return CancelResult.PREPARATION;
        }
        ActiveJob job = active;
        if (job != null && playerId.equals(job.playerId)) {
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
        ActiveJob job = active;
        if (job == null) return;
        // NeoForge's own tick budget is the first guard: under load the rollback simply waits.
        if (!event.hasTime()) return;
        try {
            if (job.tick(event.getServer())) active = null;
        } catch (Throwable t) {
            active = null;
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Tick-budgeted rollback failed", t);
            job.onError.accept("Откат остановлен из-за ошибки: " + concise(t));
        }
    }

    public static void shutdown() {
        for (Preparation preparation : PREPARATIONS.values()) preparation.cancelled = true;
        PREPARATIONS.clear();
        active = null;
        ThreadPoolExecutor ex = executor;
        executor = null;
        if (ex != null) ex.shutdownNow();
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
            current = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4), factory, new ThreadPoolExecutor.AbortPolicy());
            current.prestartAllCoreThreads();
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
            int chunkLoads = 0;

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
                        if (chunkLoads >= chunkLoadBudget) return false;
                        level.getChunk(first.pos().getX() >> 4, first.pos().getZ() >> 4);
                        chunkLoads++;
                        if (!level.hasChunkAt(first.pos())) return false;
                    }
                    int chunkX = first.pos().getX() >> 4;
                    int chunkZ = first.pos().getZ() >> 4;
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
                        if (chunkLoads >= chunkLoadBudget) return false;
                        level.getChunk(entry.x >> 4, entry.z >> 4);
                        chunkLoads++;
                        if (!level.hasChunkAt(pos)) return false;
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
                        if (chunkLoads >= chunkLoadBudget) return false;
                        level.getChunk(entry.x >> 4, entry.z >> 4);
                        chunkLoads++;
                        if (!level.hasChunkAt(pos)) return false;
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
