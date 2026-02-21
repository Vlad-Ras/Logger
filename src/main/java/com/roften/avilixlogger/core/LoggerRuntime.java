package com.roften.avilixlogger.core;

import net.minecraft.world.level.Level;

import com.roften.avilixlogger.AvilixLoggerMod;

/**
 * Lazy-initialized runtime context.
 */
public final class LoggerRuntime {
    private static volatile LogStorage STORAGE;

    private LoggerRuntime() {}

    public static LogStorage storage(Level level) {
        if (STORAGE != null) return STORAGE;
        synchronized (LoggerRuntime.class) {
            if (STORAGE != null) return STORAGE;
            // DB-backed storage (XAMPP/MySQL) with async writer.
            try {
                STORAGE = new MysqlLogStorage();
            } catch (Throwable t) {
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] MySQL storage init failed. Logging will be disabled until the DB is fixed.", t);
                STORAGE = new NoopLogStorage();
            }
            return STORAGE;
        }
    }

    public static void shutdown() {
        LogStorage s = STORAGE;
        if (s != null) s.shutdown();
        STORAGE = null;
    }
}
