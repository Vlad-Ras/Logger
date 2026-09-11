package com.roften.avilixlogger.core;

import net.minecraft.world.level.Level;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.api.LogAdapterRegistry;

/**
 * Lazy-initialized runtime context.
 */
public final class LoggerRuntime {
    private static final NoopLogStorage UNAUTHORIZED = new NoopLogStorage();
    private static final PendingLogStorage PENDING = new PendingLogStorage();
    private static final DeduplicatingLogStorage FRONT = new DeduplicatingLogStorage(PENDING);

    private static volatile LogStorage STORAGE;
    private static volatile boolean INIT_STARTED;
    private static volatile long INIT_GENERATION;
    private static volatile Thread INIT_THREAD;

    private LoggerRuntime() {}

    public static LogStorage storage(Level level) {
        if (!LoggerConfig.isEnabled()) return UNAUTHORIZED;
        if (STORAGE == null) startAsyncInit();
        return FRONT;
    }

    public static void warmupAsync() {
        if (!LoggerConfig.isEnabled()) return;
        startAsyncInit();
    }

    private static void startAsyncInit() {
        if (!LoggerConfig.isEnabled()) return;
        if (INIT_STARTED) return;
        synchronized (LoggerRuntime.class) {
            if (INIT_STARTED) return;
            INIT_STARTED = true;
            long generation = ++INIT_GENERATION;

            Thread t = new Thread(() -> {
                int attempt = 0;
                try {
                    LogAdapterRegistry.discover();
                    while (INIT_STARTED && INIT_GENERATION == generation && LoggerConfig.isEnabled()) {
                        attempt++;
                        LogStorage real;
                        try {
                            real = createConfiguredStorage();
                        } catch (Throwable failure) {
                            if (attempt == 1 || attempt % 12 == 0) {
                                AvilixLoggerMod.LOGGER.error(
                                        "[AvilixLogger] ClickHouse initialization failed (attempt {}). "
                                                + "Logging is queued and connection will retry automatically.",
                                        attempt,
                                        failure
                                );
                            } else {
                                AvilixLoggerMod.LOGGER.warn(
                                        "[AvilixLogger] ClickHouse is still unavailable (attempt {}): {}",
                                        attempt,
                                        rootMessage(failure)
                                );
                            }
                            if (!sleepBeforeRetry(generation)) return;
                            continue;
                        }

                        if (!INIT_STARTED || INIT_GENERATION != generation || !LoggerConfig.isEnabled()) {
                            real.shutdown();
                            return;
                        }
                        PENDING.setDelegate(real);
                        STORAGE = real;
                        if (attempt > 1) {
                            AvilixLoggerMod.LOGGER.info(
                                    "[AvilixLogger] ClickHouse connection recovered after {} attempts; queued logs are being flushed.",
                                    attempt
                            );
                        }
                        return;
                    }
                } catch (Throwable fatal) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Storage initializer stopped unexpectedly.", fatal);
                } finally {
                    if (INIT_GENERATION == generation && STORAGE == null && !LoggerConfig.isEnabled()) {
                        INIT_STARTED = false;
                    }
                    if (INIT_THREAD == Thread.currentThread()) INIT_THREAD = null;
                }
            }, "avilixlogger-storage-init");
            t.setDaemon(true);
            INIT_THREAD = t;
            t.start();
        }
    }

    private static boolean sleepBeforeRetry(long generation) {
        long delayMs;
        try {
            delayMs = Math.max(1_000L, LoggerConfig.VALUES.clickHouseHealthCooldownMs.get());
        } catch (Throwable ignored) {
            delayMs = 10_000L;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return INIT_STARTED && INIT_GENERATION == generation && !Thread.currentThread().isInterrupted();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank()
                ? String.valueOf(current == null ? error : current)
                : message;
    }

    public static boolean isInitializing() {
        return LoggerConfig.isEnabled() && INIT_STARTED && STORAGE == null;
    }

    private static LogStorage createConfiguredStorage() {
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting ClickHouse storage (MySQL is not read or migrated)");
        return new ClickHouseLogStorage();
    }

    public static void shutdown() {
        INIT_STARTED = false;
        INIT_GENERATION++;
        Thread initThread = INIT_THREAD;
        if (initThread != null) initThread.interrupt();
        AsyncLogProcessor.shutdown();
        ChatAuditLogger.clearPending();
        AdaptiveLogDiagnostics.logSummary();
        LogStorage s = STORAGE;
        if (s != null) {
            s.shutdown();
        } else {
            PENDING.shutdown();
        }
        STORAGE = null;
        INIT_THREAD = null;
        PENDING.reset();
        FRONT.clear();
    }
}
