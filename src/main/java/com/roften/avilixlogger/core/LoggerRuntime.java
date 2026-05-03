package com.roften.avilixlogger.core;

import net.minecraft.world.level.Level;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

import java.util.Locale;

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
        String backend;
        try {
            backend = LoggerConfig.VALUES.storageBackend.get();
        } catch (Throwable t) {
            backend = "mysql";
        }
        backend = backend == null ? "mysql" : backend.trim().toLowerCase(Locale.ROOT);

        return switch (backend) {
            case "clickhouse", "ch" -> {
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting ClickHouse storage backend");
                yield new ClickHouseLogStorage();
            }
            case "dual", "both" -> createDualStorage();
            case "mysql", "mariadb", "sql", "" -> {
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting MySQL storage backend");
                yield new MysqlLogStorage();
            }
            default -> {
                AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Unknown storageBackend='{}'. Falling back to MySQL.", backend);
                yield new MysqlLogStorage();
            }
        };
    }

    private static LogStorage createDualStorage() {
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting dual storage backend: ClickHouse + MySQL");
        LogStorage clickHouse = null;
        LogStorage mysql = null;

        try {
            clickHouse = new ClickHouseLogStorage();
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse init failed in dual mode", t);
        }

        try {
            mysql = new MysqlLogStorage();
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] MySQL init failed in dual mode", t);
        }

        if (clickHouse != null && mysql != null) {
            boolean preferClickHouse;
            try {
                preferClickHouse = LoggerConfig.VALUES.dualPreferClickHouseReads.get();
            } catch (Throwable t) {
                preferClickHouse = true;
            }

            String writeMode;
            try {
                writeMode = LoggerConfig.VALUES.dualWriteMode.get();
            } catch (Throwable t) {
                writeMode = "mirror";
            }
            writeMode = writeMode == null ? "mirror" : writeMode.trim().toLowerCase(Locale.ROOT);

            boolean forceClickHousePrimary = "clickhouse_only".equals(writeMode) || "ch_only".equals(writeMode);
            boolean useClickHousePrimary = preferClickHouse || forceClickHousePrimary;

            LogStorage primary = useClickHousePrimary ? clickHouse : mysql;
            LogStorage fallback = useClickHousePrimary ? mysql : clickHouse;
            String primaryName = useClickHousePrimary ? "ClickHouse" : "MySQL";
            String fallbackName = useClickHousePrimary ? "MySQL" : "ClickHouse";

            String readMode;
            try {
                readMode = LoggerConfig.VALUES.dualReadMode.get();
            } catch (Throwable t) {
                readMode = "primary_fallback";
            }

            if ("failover".equals(writeMode) || "primary_fallback".equals(writeMode) || "clickhouse_failover".equals(writeMode)) {
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] Dual storage write mode: failover. Primary={}, fallback={}, readMode={}", primaryName, fallbackName, readMode);
                return new PriorityFailoverLogStorage(primary, fallback, primaryName, fallbackName, readMode);
            }

            if ("primary_only".equals(writeMode) || "preferred_only".equals(writeMode) || "clickhouse_only".equals(writeMode) || "ch_only".equals(writeMode)) {
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] Dual storage write mode: primary_only. Writes only to {}, reads use readMode={} with {} available as read-only fallback/source.", primaryName, readMode, fallbackName);
                return new DualLogStorage(primary, fallback, true, readMode, "primary_only");
            }

            AvilixLoggerMod.LOGGER.info("[AvilixLogger] Dual storage write mode: mirror. Reads prefer {}, readMode={}", primaryName, readMode);
            return new DualLogStorage(primary, fallback, true, readMode, "mirror");
        }
        if (clickHouse != null) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual mode degraded to ClickHouse-only because MySQL failed.");
            return clickHouse;
        }
        if (mysql != null) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual mode degraded to MySQL-only because ClickHouse failed.");
            return mysql;
        }

        AvilixLoggerMod.LOGGER.error("[AvilixLogger] Both storages failed in dual mode. Logging disabled.");
        return new NoopLogStorage();
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
