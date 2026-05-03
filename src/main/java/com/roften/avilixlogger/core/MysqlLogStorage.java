package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.AvilixLoggerMod;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import net.minecraft.core.BlockPos;

import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * MySQL storage (XAMPP compatible).
 *
 * Design goals:
 * - never block server tick thread on I/O: appends are queue-based
 * - batch inserts with prepared statements
 * - schema optimized for the dominant queries (dim+time and exact block lookups)
 */
public final class MysqlLogStorage implements HealthAwareLogStorage {


private static void loadMysqlDriver() {
    try {
        Class.forName("com.mysql.cj.jdbc.Driver");
    } catch (ClassNotFoundException e) {
        throw new IllegalStateException("MySQL Connector/J is not on the runtime classpath. Check jarJar/localRuntime dependencies.", e);
    }
}

private static void ensureDatabaseExists(String host, int port, String database, String user, String pass) {
    loadMysqlDriver();
    // Connect without a schema first, then create it if missing.
    String baseUrl = "jdbc:mysql://" + host + ":" + port + "/"
            + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&createDatabaseIfNotExist=true";
    try (Connection c = DriverManager.getConnection(baseUrl, user, pass);
         Statement st = c.createStatement()) {
        // Use utf8mb4 for full Unicode (emoji-safe).
        st.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + database.replace("`", "") + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    } catch (SQLException e) {
        // If user has no privilege to create DB, later connection will fail with a clear error.
        AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Cannot auto-create database '" + database + "'. Check privileges or create it manually.", e);
    }
}


    private static final int DEFAULT_QUEUE_CAP = 50_000;

    private final HikariDataSource ds;
    private final ArrayBlockingQueue<LogEntry> queue;
    private final Thread writer;
    private volatile boolean running = true;

    // metrics
    private volatile long dropped;
    private volatile long written;
    private volatile long unhealthyUntilMs;
    private volatile Throwable lastFailure;

    public MysqlLogStorage() {
        this.queue = new ArrayBlockingQueue<>(Math.max(10_000, LoggerConfig.VALUES.dbQueueCapacity.get()));
        this.ds = createDataSource();
        ensureSchema();

        this.writer = new Thread(this::runWriter, "avilixlogger-mysql-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public boolean isLikelyAvailable() {
        return running && System.currentTimeMillis() >= unhealthyUntilMs;
    }

    @Override
    public String storageName() {
        return "MySQL";
    }

    private void markHealthy() {
        unhealthyUntilMs = 0L;
        lastFailure = null;
    }

    private void markUnhealthy(Throwable t) {
        lastFailure = t;
        long cooldown;
        try {
            cooldown = Math.max(1000L, LoggerConfig.VALUES.dualFallbackCooldownMs.get());
        } catch (Throwable ignored) {
            cooldown = 10_000L;
        }
        unhealthyUntilMs = System.currentTimeMillis() + cooldown;
    }

    private static HikariDataSource createDataSource() {
        String host = LoggerConfig.VALUES.dbHost.get();
        int port = LoggerConfig.VALUES.dbPort.get();
        String database = LoggerConfig.VALUES.dbName.get();

        String user = LoggerConfig.VALUES.dbUser.get();
        String pass = LoggerConfig.VALUES.dbPassword.get();
        ensureDatabaseExists(host, port, database, user, pass);

        // XAMPP default: root / empty password; allow custom config.
        String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=utf8&useUnicode=true&createDatabaseIfNotExist=true";

        loadMysqlDriver();

        HikariConfig cfg = new HikariConfig();
        cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
        cfg.setJdbcUrl(url);
        cfg.setUsername(user);
        cfg.setPassword(pass);
        cfg.setMaximumPoolSize(LoggerConfig.VALUES.dbPoolSize.get());
        cfg.setMinimumIdle(Math.min(2, LoggerConfig.VALUES.dbPoolSize.get()));
        cfg.setConnectionTimeout(10_000);
        cfg.setValidationTimeout(5_000);
        cfg.setIdleTimeout(60_000);
        cfg.setMaxLifetime(10 * 60_000);

        // MySQL perf knobs (safe defaults)
        cfg.addDataSourceProperty("cachePrepStmts", "true");
        cfg.addDataSourceProperty("prepStmtCacheSize", "250");
        cfg.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        cfg.addDataSourceProperty("useServerPrepStmts", "false");
        cfg.addDataSourceProperty("rewriteBatchedStatements", "true");
        cfg.addDataSourceProperty("useLocalSessionState", "true");
        cfg.addDataSourceProperty("elideSetAutoCommits", "true");
        cfg.addDataSourceProperty("maintainTimeStats", "false");
        cfg.addDataSourceProperty("cacheServerConfiguration", "true");

        return new HikariDataSource(cfg);
    }

    private void ensureSchema() {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS avilixlogger_actions (" +
                    "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "ts BIGINT NOT NULL," +
                    "dim VARCHAR(128) NOT NULL," +
                    "x INT NOT NULL," +
                    "y INT NOT NULL," +
                    "z INT NOT NULL," +
                    "action SMALLINT NOT NULL," +
                    "actor_name VARCHAR(64) NULL," +
                    "actor_uuid BINARY(16) NULL," +
                    "data LONGBLOB NOT NULL" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");

            // MySQL/MariaDB compatibility: CREATE INDEX may not support IF NOT EXISTS.
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_ts ON avilixlogger_actions (ts)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_dim_ts ON avilixlogger_actions (dim, ts)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_dim_xyz_ts ON avilixlogger_actions (dim, x, y, z, ts)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_actor_ts ON avilixlogger_actions (actor_name, ts)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_action_ts ON avilixlogger_actions (action, ts)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_action_ts_id ON avilixlogger_actions (action, ts, id)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_dim_action_ts_id ON avilixlogger_actions (dim, action, ts, id)"); } catch (SQLException ignored) {}
            try { st.executeUpdate("CREATE INDEX idx_avilixlogger_actor_action_ts_id ON avilixlogger_actions (actor_name, action, ts, id)"); } catch (SQLException ignored) {}
        } catch (SQLException e) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Failed to ensure MySQL schema", e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public void append(LogEntry entry) {
        if (!running) return;
        boolean ok = queue.offer(entry);
        if (!ok) {
            dropped++;
        }
    }

    private void runWriter() {
        final int batchSize = Math.max(1, LoggerConfig.VALUES.dbBatchSize.get());
        final long flushEveryMs = Math.max(50, LoggerConfig.VALUES.dbFlushIntervalMs.get());
        final List<LogEntry> batch = new ArrayList<>(batchSize);

        long lastFlush = System.currentTimeMillis();

        while (running || !queue.isEmpty()) {
            try {
                LogEntry first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first != null) batch.add(first);

                queue.drainTo(batch, batchSize - batch.size());

                long now = System.currentTimeMillis();
                boolean timeFlush = (now - lastFlush) >= flushEveryMs;
                if (!batch.isEmpty() && (batch.size() >= batchSize || timeFlush)) {
                    insertBatch(batch);
                    written += batch.size();
                    batch.clear();
                    lastFlush = now;
                }

                // periodic cleanup (cheap)
                if (timeFlush) {
                    maybeCleanup(now);
                }
            } catch (InterruptedException ignored) {
                // ignore
            } catch (Throwable t) {
                markUnhealthy(t);
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] MySQL writer failure", t);
                try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
            }
        }

        // last flush
        if (!batch.isEmpty()) {
            try {
                insertBatch(batch);
                written += batch.size();
            } catch (Throwable t) {
                markUnhealthy(t);
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] MySQL final flush failure", t);
            }
        }
    }

    private volatile long lastCleanupAt = 0L;

    private void maybeCleanup(long now) {
        if (!running) return;

        int keepDays = LoggerConfig.VALUES.keepDays.get();
        if (keepDays <= 0) return;

        long everyMs = 60 * 60_000L;
        if ((now - lastCleanupAt) < everyMs) return;
        lastCleanupAt = now;

        long cutoff = now - (long) keepDays * 24L * 60L * 60_000L;
        int cleanupBatchSize = Math.max(100, LoggerConfig.VALUES.dbCleanupBatchSize.get());
        int maxBatches = Math.max(1, LoggerConfig.VALUES.dbCleanupMaxBatchesPerRun.get());
        int timeoutSec = Math.max(1, LoggerConfig.VALUES.dbCleanupQueryTimeoutSec.get());

        int totalDeleted = 0;
        try (Connection c = ds.getConnection()) {
            for (int i = 0; i < maxBatches && running; i++) {
                try (PreparedStatement ps = c.prepareStatement(
                        "DELETE FROM avilixlogger_actions WHERE ts < ? ORDER BY ts LIMIT ?")) {
                    ps.setQueryTimeout(timeoutSec);
                    ps.setLong(1, cutoff);
                    ps.setInt(2, cleanupBatchSize);

                    int deleted = ps.executeUpdate();
                    totalDeleted += deleted;
                    if (deleted < cleanupBatchSize) {
                        break;
                    }
                }
            }

            if (totalDeleted > 0) {
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] Cleanup removed {} old rows", totalDeleted);
            }
        } catch (SQLException e) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Cleanup failed", e);
        }
    }

    private void insertBatch(List<LogEntry> batch) throws SQLException {
        List<LogEntry> toWrite = coalesceBatch(batch);
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO avilixlogger_actions (id, ts, dim, x, y, z, action, actor_name, actor_uuid, data) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
            for (LogEntry e : toWrite) {
                long id = e.id > 0 ? e.id : LogIdGenerator.ensure(e);
                ps.setLong(1, id);
                ps.setLong(2, e.ts);
                ps.setString(3, e.dim);
                ps.setInt(4, e.x);
                ps.setInt(5, e.y);
                ps.setInt(6, e.z);
                ps.setInt(7, e.type.ordinal());
                ps.setString(8, e.actorName);
                if (e.actorUuid != null) {
                    ps.setBytes(9, UuidBytes.toBytes(e.actorUuid));
                } else {
                    ps.setNull(9, Types.BINARY);
                }
                ps.setBytes(10, GzipJson.toGzippedJsonBytes(e));
                ps.addBatch();
            }
            ps.executeBatch();
            markHealthy();
        }
    }

    /** Lossless per-flush coalescing for spammy container deltas. */
    private static List<LogEntry> coalesceBatch(List<LogEntry> in) {
        if (in == null || in.size() < 2) return in;
        LinkedHashMap<String, LogEntry> merged = new LinkedHashMap<>(in.size());
        ArrayList<LogEntry> out = new ArrayList<>(in.size());
        for (LogEntry e : in) {
            if (!isCoalescibleDelta(e)) {
                out.add(e);
                continue;
            }
            String key = coalesceKey(e);
            LogEntry prev = merged.get(key);
            if (prev == null) {
                merged.put(key, e);
                out.add(e);
            } else {
                prev.count += Math.max(0, e.count);
                if (e.ts < prev.ts) prev.ts = e.ts;
            }
        }
        return out;
    }

    private static boolean isCoalescibleDelta(LogEntry e) {
        if (e == null) return false;
        if (e.type != ActionType.CONTAINER_PUT && e.type != ActionType.CONTAINER_TAKE) return false;
        if (e.itemStackNbt == null || e.itemStackNbt.isBlank()) return false;
        if (e.count <= 0) return false;
        return isBlank(e.beBefore) && isBlank(e.beAfter)
                && isBlank(e.containerSlotsBefore) && isBlank(e.containerSlotsAfter)
                && isBlank(e.playerInvBefore) && isBlank(e.playerInvAfter);
    }

    private static String coalesceKey(LogEntry e) {
        return e.type.ordinal() + "|" + e.dim + "|" + e.x + '|' + e.y + '|' + e.z
                + "|" + Objects.toString(e.actorUuid, "")
                + "|" + Objects.toString(e.actorName, "")
                + "|" + Objects.toString(e.entityUuid, "")
                + "|" + Objects.toString(e.entityType, "")
                + "|" + Objects.toString(e.blockAfter, "")
                + "|" + Objects.toString(e.itemStackNbt, "")
                + "|" + Objects.toString(e.extra, "");
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @Override
    public List<LogEntry> query(LogQuery q) {
        return select(q, false);
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery q) {
        return select(q, true);
    }

    private List<LogEntry> select(LogQuery q, boolean reverse) {
        if (q == null) return List.of();
        String dim = q.dim;

        StringBuilder sql = new StringBuilder();
        List<Object> params = new ArrayList<>();

        // dim: null or "*" means all dimensions
        boolean allDims = (dim == null) || "*".equals(dim);
        if (allDims) {
            sql.append("SELECT id, data FROM avilixlogger_actions WHERE ts>=? AND ts<=?");
            params.add(q.sinceTs);
            params.add(q.untilTs);
        } else {
            sql.append("SELECT id, data FROM avilixlogger_actions WHERE dim=? AND ts>=? AND ts<=?");
            params.add(dim);
            params.add(q.sinceTs);
            params.add(q.untilTs);
        }

        if (q.types != null && !q.types.isEmpty()) {
            sql.append(" AND action IN (");
            int i = 0;
            for (ActionType t : q.types) {
                if (i++ > 0) sql.append(',');
                sql.append('?');
                params.add(t.ordinal());
            }
            sql.append(')');
        } else if (q.type != null) {
            sql.append(" AND action=?");
            params.add(q.type.ordinal());
        }
        if (q.actorName != null && !q.actorName.isBlank()) {
            sql.append(" AND actor_name=?");
            params.add(q.actorName);
        }

        if (q.exactPos != null) {
            sql.append(" AND x=? AND y=? AND z=?");
            params.add(q.exactPos.getX());
            params.add(q.exactPos.getY());
            params.add(q.exactPos.getZ());
        } else if (q.minPos != null && q.maxPos != null) {
            BlockPos min = q.minPos;
            BlockPos max = q.maxPos;
            sql.append(" AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ?");
            params.add(Math.min(min.getX(), max.getX()));
            params.add(Math.max(min.getX(), max.getX()));
            params.add(Math.min(min.getY(), max.getY()));
            params.add(Math.max(min.getY(), max.getY()));
            params.add(Math.min(min.getZ(), max.getZ()));
            params.add(Math.max(min.getZ(), max.getZ()));
        }

        if (reverse) {
            if (q.beforeId > 0) {
                sql.append(" AND id < ?");
                params.add(q.beforeId);
            }
            sql.append(" ORDER BY id DESC");
        } else {
            if (q.afterId > 0) {
                sql.append(" AND id > ?");
                params.add(q.afterId);
            }
            sql.append(" ORDER BY id ASC");
        }
        sql.append(" LIMIT ?");
        params.add(Math.max(1, q.limit));

        List<LogEntry> out = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql.toString())) {
            try { ps.setQueryTimeout(Math.max(1, LoggerConfig.VALUES.dbSelectQueryTimeoutSec.get())); } catch (Throwable ignored) {}
            for (int i = 0; i < params.size(); i++) {
                Object v = params.get(i);
                if (v instanceof String s) ps.setString(i + 1, s);
                else if (v instanceof Integer n) ps.setInt(i + 1, n);
                else if (v instanceof Long l) ps.setLong(i + 1, l);
                else ps.setObject(i + 1, v);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byte[] data = rs.getBytes("data");
                    if (data == null) continue;
                    LogEntry e = GzipJson.fromGzippedJsonBytes(data, LogEntry.class);
                    e.id = rs.getLong("id");
                    out.add(e);
                }
            }
            markHealthy();
        } catch (SQLException e) {
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] MySQL query failed", e);
        }
        return out;
    }

    @Override
    public void shutdown() {
        running = false;
        writer.interrupt();
        try { writer.join(5_000L); } catch (InterruptedException ignored) {}

        if (writer.isAlive()) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Writer thread did not stop within timeout; closing datasource anyway.");
        }

        ds.close();
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Storage shutdown. written={}, dropped={}", written, dropped);
    }
}
