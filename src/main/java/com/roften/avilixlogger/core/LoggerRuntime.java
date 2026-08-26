package com.roften.avilixlogger.core;

import net.minecraft.world.level.Level;

import com.roften.avilixlogger.AvilixLoggerMod;

/**
 * Lazy-initialized runtime context.
 */
public final class LoggerRuntime {
    private static final PendingLogStorage PENDING = new PendingLogStorage();

    private static volatile LogStorage STORAGE;
    private static volatile boolean INIT_STARTED;

    private LoggerRuntime() {}

    public static LogStorage storage(Level level) {
        LogStorage s = STORAGE;
        if (s != null) return s;

        startAsyncInit();
        return PENDING;
    }

    public static void warmupAsync() {
        startAsyncInit();
    }

    private static void startAsyncInit() {
        if (INIT_STARTED) return;
        synchronized (LoggerRuntime.class) {
            if (INIT_STARTED) return;
            INIT_STARTED = true;

            Thread t = new Thread(() -> {
                LogStorage real;
                try {
                    real = createConfiguredStorage();
                } catch (Throwable t1) {
                    AvilixLoggerMod.LOGGER.error(
                            "[AvilixLogger] Storage init failed. Logging will be disabled until the DB is fixed.",
                            t1
                    );
                    real = new NoopLogStorage();
                }

                PENDING.setDelegate(real);
                STORAGE = real;
            }, "avilixlogger-storage-init");
            t.setDaemon(true);
            t.start();
        }
    }

    private static LogStorage createConfiguredStorage() {
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting ClickHouse storage (MySQL is not read or migrated)");
        return new ClickHouseLogStorage();
    }

    public static void shutdown() {
        AsyncLogProcessor.shutdown();
        LogStorage s = STORAGE;
        if (s != null) {
            s.shutdown();
        } else {
            PENDING.shutdown();
        }
        STORAGE = null;
        INIT_STARTED = false;
        PENDING.reset();
    }
}
