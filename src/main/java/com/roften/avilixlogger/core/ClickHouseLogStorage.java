package com.roften.avilixlogger.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * ClickHouse storage backend using the HTTP API.
 *
 * The optimized default schema is split by event domain instead of writing every event into one
 * generic table. This keeps hot GUI/rollback queries on narrow tables and avoids scanning a giant
 * compressed JSON blob for every filter. A previous unified ClickHouse table can optionally remain
 * readable, but MySQL is never read or migrated.
 */
public final class ClickHouseLogStorage implements HealthAwareLogStorage {

    private enum TableKind {
        FEED("feed"),
        BLOCKS("blocks"),
        CONTAINERS("containers"),
        ENTITIES("entities"),
        ITEMS("items"),
        PLAYERS("players"),
        CHAT("chat"),
        COMPAT("compat"),
        LEGACY("legacy");

        final String suffix;
        TableKind(String suffix) { this.suffix = suffix; }
    }

    private static final EnumMap<ActionType, TableKind> ACTION_TABLE = new EnumMap<>(ActionType.class);
    static {
        map(TableKind.BLOCKS,
                ActionType.BLOCK_BREAK,
                ActionType.BLOCK_PLACE,
                ActionType.BLOCK_INTERACT,
                ActionType.BLOCK_USE,
                ActionType.BLOCK_ENTITY_NBT_CHANGE);
        map(TableKind.CONTAINERS,
                ActionType.CONTAINER_OPEN,
                ActionType.CONTAINER_PUT,
                ActionType.CONTAINER_TAKE,
                ActionType.ENTITY_CONTAINER_OPEN);
        map(TableKind.ENTITIES,
                ActionType.ENTITY_DEATH,
                ActionType.ENTITY_SPAWN,
                ActionType.ENTITY_MOUNT,
                ActionType.ENTITY_DISMOUNT,
                ActionType.ENTITY_INTERACT,
                ActionType.ENTITY_ATTACK,
                ActionType.PROJECTILE_SHOOT,
                ActionType.PROJECTILE_HIT,
                ActionType.ENTITY_OWNER_SET);
        map(TableKind.ITEMS,
                ActionType.ITEM_DROP,
                ActionType.ITEM_PICKUP,
                ActionType.ITEM_CRAFT,
                ActionType.ITEM_SMELT,
                ActionType.ITEM_USE,
                ActionType.ITEM_USE_START,
                ActionType.ITEM_USE_STOP,
                ActionType.ITEM_CONSUME);
        map(TableKind.PLAYERS,
                ActionType.PLAYER_DEATH,
                ActionType.PLAYER_JOIN,
                ActionType.PLAYER_LEAVE,
                ActionType.PLAYER_DIMENSION_CHANGE,
                ActionType.PLAYER_RESPAWN,
                ActionType.GUI_OPEN);
        map(TableKind.CHAT,
                ActionType.CHAT_MESSAGE);
        map(TableKind.COMPAT,
                ActionType.PLANE_PLACE,
                ActionType.PLANE_REMOVE,
                ActionType.PLANE_MOUNT,
                ActionType.PLANE_PICKUP,
                ActionType.TRAIN_ASSEMBLE,
                ActionType.TRAIN_DISASSEMBLE,
                ActionType.TRAIN_SCHEDULE_TAKE,
                ActionType.TRAIN_CONTROL_START,
                ActionType.TRAIN_CONTROL_STOP,
                ActionType.TRAIN_SCHEDULE_PUT);
    }

    private static void map(TableKind kind, ActionType... types) {
        for (ActionType t : types) ACTION_TABLE.put(t, kind);
    }

    private final ArrayBlockingQueue<LogEntry> queue;
    private final Thread writer;
    private final String endpoint;
    private final String user;
    private final String password;
    private final String database;
    private final String legacyTable;
    private final String tablePrefix;
    private final boolean splitSchema;
    private final boolean readLegacyUnifiedTable;
    private final boolean zstd;
    private final boolean useFeedTable;
    private final boolean storeRollbackDetails;
    private final boolean storeNonRollbackDetails;
    private final String detailMode;
    private final boolean addSkippingIndexes;
    private final boolean asyncInsert;
    private final boolean waitForAsyncInsert;

    private volatile boolean running = true;
    private volatile long dropped;
    private volatile long written;
    private volatile long lastCleanupAt;
    private volatile long unhealthyUntilMs;
    private volatile Throwable lastFailure;
    private volatile boolean useActorNameLcFilter = true;
    private volatile boolean useSpatialHelperColumns = true;

    public ClickHouseLogStorage() {
        this.endpoint = normalizeHttpEndpoint(LoggerConfig.VALUES.clickHouseUrl.get());
        this.user = safeString(LoggerConfig.VALUES.clickHouseUser.get());
        this.password = safeString(LoggerConfig.VALUES.clickHousePassword.get());
        this.database = LoggerConfig.VALUES.clickHouseDatabase.get();
        this.legacyTable = LoggerConfig.VALUES.clickHouseTable.get();
        this.tablePrefix = sanitizePrefix(LoggerConfig.VALUES.clickHouseTablePrefix.get(), this.legacyTable);
        this.splitSchema = !"legacy".equalsIgnoreCase(safeString(LoggerConfig.VALUES.clickHouseSchemaMode.get()).trim());
        this.readLegacyUnifiedTable = LoggerConfig.VALUES.clickHouseReadLegacyUnifiedTable.get();
        this.zstd = LoggerConfig.VALUES.clickHouseUseZstdCodec.get();
        this.useFeedTable = LoggerConfig.VALUES.clickHouseUseFeedTable.get();
        this.storeRollbackDetails = LoggerConfig.VALUES.clickHouseStoreRollbackDetails.get();
        this.storeNonRollbackDetails = LoggerConfig.VALUES.clickHouseStoreNonRollbackDetails.get();
        this.detailMode = safeString(LoggerConfig.VALUES.clickHouseDetailMode.get()).trim().toLowerCase(Locale.ROOT);
        this.addSkippingIndexes = LoggerConfig.VALUES.clickHouseAddSkippingIndexes.get();
        this.asyncInsert = LoggerConfig.VALUES.clickHouseAsyncInsert.get();
        this.waitForAsyncInsert = LoggerConfig.VALUES.clickHouseWaitForAsyncInsert.get();
        this.queue = new ArrayBlockingQueue<>(Math.max(10_000, LoggerConfig.VALUES.clickHouseQueueCapacity.get()));

        ensureSchema();
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] ClickHouse connected successfully. endpoint={}, database={}, schemaMode={}, feed={}, queueCapacity={}, batchSize={}",
                endpoint, database, splitSchema ? "split" : "legacy", useFeedTable, queue.remainingCapacity() + queue.size(), LoggerConfig.VALUES.clickHouseBatchSize.get());

        this.writer = new Thread(this::runWriter, "avilixlogger-clickhouse-writer");
        this.writer.setDaemon(true);
        this.writer.start();
    }

    @Override
    public boolean isLikelyAvailable() {
        return running && System.currentTimeMillis() >= unhealthyUntilMs;
    }

    @Override
    public String storageName() {
        return splitSchema ? (useFeedTable ? "ClickHouse(split+feed)" : "ClickHouse(split)") : "ClickHouse(legacy)";
    }

    private void markHealthy() {
        unhealthyUntilMs = 0L;
        lastFailure = null;
    }

    private void markUnhealthy(Throwable t) {
        lastFailure = t;
        long cooldown;
        try {
            cooldown = Math.max(1000L, LoggerConfig.VALUES.clickHouseHealthCooldownMs.get());
        } catch (Throwable ignored) {
            cooldown = 10_000L;
        }
        unhealthyUntilMs = System.currentTimeMillis() + cooldown;
    }

    private void ensureSchema() {
        String db = quoteIdent(database);
        try {
            execute("CREATE DATABASE IF NOT EXISTS " + db, timeoutSec());
            if (splitSchema) {
                if (useFeedTable) ensureFeedTable();
                for (TableKind kind : splitKinds()) ensureSplitTable(kind);
                ensureSplitSchemaCompatibility();
                if (addSkippingIndexes) ensureSkippingIndexes();
                ensureRetentionPolicies();
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] ClickHouse split+feed schema ready. DB={}, prefix={}, feed={}, detailMode={}", database, tablePrefix, useFeedTable, detailMode);
            } else {
                ensureLegacyTable();
                ensureRetentionPolicies();
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] ClickHouse legacy unified schema ready. DB={}, table={}", database, legacyTable);
            }
            markHealthy();
        } catch (Throwable e) {
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Failed to ensure ClickHouse schema via HTTP", e);
            throw new RuntimeException(e);
        }
    }

    private void ensureLegacyTable() throws IOException {
        String ttl = ttlClause("ts_ms", LoggerConfig.VALUES.keepDays.get());
        execute("CREATE TABLE IF NOT EXISTS " + qualifiedLegacyTable() + " (" +
                "id UInt64," +
                "ts_ms UInt64," +
                "dim LowCardinality(String)," +
                "x Int32," +
                "y Int32," +
                "z Int32," +
                "action UInt16," +
                "actor_name LowCardinality(String)," +
                "actor_uuid String," +
                "data String" + largeCodec() +
                ") ENGINE = MergeTree " +
                "PARTITION BY toYYYYMM(toDateTime(intDiv(ts_ms, 1000))) " +
                "ORDER BY (dim, action, actor_name, ts_ms, id)" + ttl +
                " SETTINGS index_granularity = 8192", timeoutSec());
    }


    private void ensureFeedTable() throws IOException {
        String table = qualifiedFeedTable();
        String ttl = ttlClause("ts_ms", retentionDaysFor(TableKind.FEED));
        String codec = largeCodec();
        execute("CREATE TABLE IF NOT EXISTS " + table + " (" +
                "id UInt64," +
                "ts_ms UInt64," +
                "dim LowCardinality(String)," +
                "x Int32," +
                "y Int32," +
                "z Int32," +
                "chunk_x Int32," +
                "chunk_z Int32," +
                "region_x Int32," +
                "region_z Int32," +
                "action UInt16," +
                "category LowCardinality(String)," +
                "actor_name LowCardinality(String)," +
                "actor_name_lc LowCardinality(String)," +
                "actor_uuid String," +
                "target_kind LowCardinality(String)," +
                "target_id String," +
                "item_count Int32," +
                "source LowCardinality(String)," +
                "short_text String" + codec + "," +
                "extra String" + codec + "," +
                "has_details UInt8," +
                "source_table LowCardinality(String)," +
                "source_id UInt64" +
                ") ENGINE = MergeTree " +
                "PARTITION BY toYYYYMM(toDateTime(intDiv(ts_ms, 1000))) " +
                "ORDER BY (dim, region_x, region_z, chunk_x, chunk_z, action, ts_ms, id)" + ttl +
                " SETTINGS index_granularity = 8192", timeoutSec());
    }

    private void ensureSplitTable(TableKind kind) throws IOException {
        String table = qualifiedSplitTable(kind);
        String ttl = ttlClause("ts_ms", retentionDaysFor(kind));
        String cols = switch (kind) {
            case BLOCKS -> commonColumns() +
                    ", block_before String" + largeCodec() +
                    ", be_before String" + largeCodec() +
                    ", block_after String" + largeCodec() +
                    ", be_after String" + largeCodec();
            case CONTAINERS -> commonColumns() +
                    ", block_before String" + largeCodec() +
                    ", be_before String" + largeCodec() +
                    ", block_after String" + largeCodec() +
                    ", be_after String" + largeCodec() +
                    ", entity_type LowCardinality(String)" +
                    ", entity_uuid String" +
                    ", item_stack String" + largeCodec() +
                    ", count Int32" +
                    ", container_slots_before String" + largeCodec() +
                    ", container_slots_after String" + largeCodec();
            case ENTITIES -> commonColumns() +
                    ", entity_type LowCardinality(String)" +
                    ", entity_uuid String" +
                    ", entity_nbt String" + largeCodec() +
                    ", item_stack String" + largeCodec() +
                    ", count Int32";
            case ITEMS -> commonColumns() +
                    ", item_stack String" + largeCodec() +
                    ", count Int32" +
                    ", player_inv_before String" + largeCodec() +
                    ", player_inv_after String" + largeCodec();
            case PLAYERS -> commonColumns() +
                    ", entity_uuid String" +
                    ", entity_nbt String" + largeCodec() +
                    ", player_inv_before String" + largeCodec() +
                    ", player_inv_after String" + largeCodec();
            case CHAT -> commonColumns() +
                    ", message String" + largeCodec();
            case COMPAT -> commonColumns() +
                    ", block_before String" + largeCodec() +
                    ", be_before String" + largeCodec() +
                    ", block_after String" + largeCodec() +
                    ", be_after String" + largeCodec() +
                    ", entity_type LowCardinality(String)" +
                    ", entity_uuid String" +
                    ", entity_nbt String" + largeCodec() +
                    ", item_stack String" + largeCodec() +
                    ", count Int32" +
                    ", player_inv_before String" + largeCodec() +
                    ", player_inv_after String" + largeCodec() +
                    ", container_slots_before String" + largeCodec() +
                    ", container_slots_after String" + largeCodec();
            default -> throw new IllegalArgumentException("Unexpected table kind: " + kind);
        };

        String orderBy = switch (kind) {
            case BLOCKS, CONTAINERS -> "(dim, region_x, region_z, chunk_x, chunk_z, action, ts_ms, id)";
            case CHAT -> "(dim, actor_name_lc, ts_ms, id)";
            default -> "(dim, action, actor_name_lc, ts_ms, id)";
        };

        execute("CREATE TABLE IF NOT EXISTS " + table + " (" + cols + ") ENGINE = MergeTree " +
                "PARTITION BY toYYYYMM(toDateTime(intDiv(ts_ms, 1000))) " +
                "ORDER BY " + orderBy + ttl +
                " SETTINGS index_granularity = 8192", timeoutSec());
    }

    /**
     * Older split-schema builds created a subset of the columns used by the current reader.
     * CREATE TABLE IF NOT EXISTS does not evolve those tables, so a WORLD/player lookup could be
     * killed by a single missing column inside the UNION and the GUI would simply show no rows.
     *
     * Keep these ALTERs best-effort: logging must continue even if a ClickHouse version rejects a
     * codec/default expression. Runtime query code also avoids relying on actor_name_lc directly.
     */
    private void ensureSplitSchemaCompatibility() {
        if (useFeedTable) {
            String feed = qualifiedFeedTable();
            addCommonCompatColumns(feed);
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS category LowCardinality(String) DEFAULT ''");
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS target_kind LowCardinality(String) DEFAULT ''");
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS target_id String DEFAULT ''" + largeCodec());
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS item_count Int32 DEFAULT 0");
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS short_text String DEFAULT ''" + largeCodec());
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS has_details UInt8 DEFAULT 0");
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS source_table LowCardinality(String) DEFAULT ''");
            tryExecute("ALTER TABLE " + feed + " ADD COLUMN IF NOT EXISTS source_id UInt64 DEFAULT id");
        }

        for (TableKind kind : splitKinds()) {
            String table = qualifiedSplitTable(kind);
            addCommonCompatColumns(table);
            switch (kind) {
                case BLOCKS -> addBlockCompatColumns(table);
                case CONTAINERS -> {
                    addBlockCompatColumns(table);
                    addEntityCompatColumns(table, false);
                    addItemCompatColumns(table);
                    addContainerCompatColumns(table);
                }
                case ENTITIES -> {
                    addEntityCompatColumns(table, true);
                    addItemCompatColumns(table);
                }
                case ITEMS -> {
                    addItemCompatColumns(table);
                    addPlayerInventoryCompatColumns(table);
                }
                case PLAYERS -> {
                    tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS entity_uuid String DEFAULT ''");
                    tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS entity_nbt String DEFAULT ''" + largeCodec());
                    addPlayerInventoryCompatColumns(table);
                }
                case CHAT -> tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS message String DEFAULT ''" + largeCodec());
                case COMPAT -> {
                    addBlockCompatColumns(table);
                    addEntityCompatColumns(table, true);
                    addItemCompatColumns(table);
                    addPlayerInventoryCompatColumns(table);
                    addContainerCompatColumns(table);
                }
                default -> {}
            }
        }
    }

    private void addCommonCompatColumns(String table) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS chunk_x Int32 DEFAULT intDiv(x, 16)");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS chunk_z Int32 DEFAULT intDiv(z, 16)");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS region_x Int32 DEFAULT intDiv(x, 512)");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS region_z Int32 DEFAULT intDiv(z, 512)");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS actor_name_lc LowCardinality(String) DEFAULT lowerUTF8(actor_name)");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS source LowCardinality(String) DEFAULT ''");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS extra String DEFAULT ''" + largeCodec());
    }

    private void addBlockCompatColumns(String table) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS block_before String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS be_before String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS block_after String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS be_after String DEFAULT ''" + largeCodec());
    }

    private void addEntityCompatColumns(String table, boolean includeNbt) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS entity_type LowCardinality(String) DEFAULT ''");
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS entity_uuid String DEFAULT ''");
        if (includeNbt) tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS entity_nbt String DEFAULT ''" + largeCodec());
    }

    private void addItemCompatColumns(String table) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS item_stack String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS count Int32 DEFAULT 0");
    }

    private void addPlayerInventoryCompatColumns(String table) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS player_inv_before String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS player_inv_after String DEFAULT ''" + largeCodec());
    }

    private void addContainerCompatColumns(String table) {
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS container_slots_before String DEFAULT ''" + largeCodec());
        tryExecute("ALTER TABLE " + table + " ADD COLUMN IF NOT EXISTS container_slots_after String DEFAULT ''" + largeCodec());
    }

    private String commonColumns() {
        return "id UInt64," +
                "ts_ms UInt64," +
                "dim LowCardinality(String)," +
                "x Int32," +
                "y Int32," +
                "z Int32," +
                "chunk_x Int32," +
                "chunk_z Int32," +
                "region_x Int32," +
                "region_z Int32," +
                "action UInt16," +
                "actor_name LowCardinality(String)," +
                "actor_name_lc LowCardinality(String)," +
                "actor_uuid String," +
                "source LowCardinality(String)," +
                "extra String" + largeCodec();
    }

    private String ttlClause(String column, int keepDays) {
        if (keepDays <= 0) return "";
        return " TTL toDateTime(intDiv(" + column + ", 1000)) + INTERVAL " + keepDays + " DAY";
    }

    private int retentionDaysFor(TableKind kind) {
        int general = LoggerConfig.VALUES.keepDays.get();
        if (kind == TableKind.FEED) {
            int v = LoggerConfig.VALUES.clickHouseFeedKeepDays.get();
            return v > 0 ? v : general;
        }
        if (kind == TableKind.CHAT) {
            int v = LoggerConfig.VALUES.clickHouseChatKeepDays.get();
            if (v > 0) return v;
        }
        if (kind == TableKind.COMPAT) {
            int v = LoggerConfig.VALUES.clickHouseCompatKeepDays.get();
            if (v > 0) return v;
        }
        int detail = LoggerConfig.VALUES.clickHouseDetailKeepDays.get();
        return detail > 0 ? detail : general;
    }

    private String largeCodec() {
        return zstd ? " CODEC(ZSTD(3))" : "";
    }

    @Override
    public void append(LogEntry entry) {
        if (!running || entry == null) return;
        boolean ok = queue.offer(entry);
        if (!ok) dropped++;
    }

    private void runWriter() {
        final int batchSize = Math.max(100, LoggerConfig.VALUES.clickHouseBatchSize.get());
        final long flushEveryMs = Math.max(100, LoggerConfig.VALUES.clickHouseFlushIntervalMs.get());
        final List<LogEntry> batch = new ArrayList<>(batchSize);
        long lastFlush = System.currentTimeMillis();

        while (running || !queue.isEmpty()) {
            try {
                LogEntry first = queue.poll(50, TimeUnit.MILLISECONDS);
                if (first != null) batch.add(first);
                queue.drainTo(batch, Math.max(0, batchSize - batch.size()));

                long now = System.currentTimeMillis();
                boolean timeFlush = (now - lastFlush) >= flushEveryMs;
                if (!batch.isEmpty() && (batch.size() >= batchSize || timeFlush)) {
                    insertBatch(batch);
                    written += batch.size();
                    batch.clear();
                    lastFlush = now;
                }

                if (timeFlush) maybeCleanup(now);
            } catch (InterruptedException ignored) {
                // shutdown wakes the writer up
            } catch (Throwable t) {
                markUnhealthy(t);
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse writer failure", t);
                try { Thread.sleep(250L); } catch (InterruptedException ignored) {}
            }
        }

        if (!batch.isEmpty()) {
            try {
                insertBatch(batch);
                written += batch.size();
            } catch (Throwable t) {
                markUnhealthy(t);
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse final flush failure", t);
            }
        }
    }

    private void maybeCleanup(long now) {
        if (!running) return;
        long everyMs = 24L * 60L * 60_000L;
        if ((now - lastCleanupAt) < everyMs) return;
        lastCleanupAt = now;

        try {
            // Native TTL cleanup is asynchronous inside ClickHouse and avoids repeated heavy
            // ALTER ... DELETE mutations competing with live inserts and history searches.
            ensureRetentionPolicies();
        } catch (Throwable e) {
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse TTL synchronization failed", e);
        }
    }

    private void ensureRetentionPolicies() {
        if (splitSchema) {
            if (useFeedTable) syncTableTtl(qualifiedFeedTable(), retentionDaysFor(TableKind.FEED));
            for (TableKind kind : splitKinds()) syncTableTtl(qualifiedSplitTable(kind), retentionDaysFor(kind));
        } else {
            syncTableTtl(qualifiedLegacyTable(), LoggerConfig.VALUES.keepDays.get());
        }
    }

    private void syncTableTtl(String table, int keepDays) {
        String sql = keepDays <= 0
                ? "ALTER TABLE " + table + " REMOVE TTL"
                : "ALTER TABLE " + table + " MODIFY TTL toDateTime(intDiv(ts_ms, 1000)) + INTERVAL " + keepDays + " DAY";
        try {
            execute(sql, timeoutSec());
        } catch (IOException error) {
            throw new IllegalStateException("Failed to synchronize ClickHouse TTL for " + table, error);
        }
    }

    private void insertBatch(List<LogEntry> batch) throws IOException {
        List<LogEntry> toWrite = coalesceBatch(batch);
        if (toWrite == null || toWrite.isEmpty()) return;

        if (!splitSchema) {
            insertLegacyBatch(toWrite);
            markHealthy();
            return;
        }

        if (useFeedTable) {
            insertFeedBatch(toWrite);
        }

        EnumMap<TableKind, ArrayList<LogEntry>> byTable = new EnumMap<>(TableKind.class);
        for (LogEntry e : toWrite) {
            if (!shouldStoreDetails(e)) continue;
            TableKind kind = tableFor(e);
            byTable.computeIfAbsent(kind, k -> new ArrayList<>()).add(e);
        }

        for (Map.Entry<TableKind, ArrayList<LogEntry>> en : byTable.entrySet()) {
            insertSplitBatch(en.getKey(), en.getValue());
        }
        markHealthy();
    }

    private void insertLegacyBatch(List<LogEntry> batch) throws IOException {
        StringBuilder body = new StringBuilder(Math.max(4096, batch.size() * 256));
        body.append("INSERT INTO ").append(qualifiedLegacyTable()).append(" FORMAT JSONEachRow\n");
        for (LogEntry e : batch) {
            LogIdGenerator.ensure(e);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("ts_ms", Math.max(0L, e.ts));
            o.addProperty("dim", safeString(e.dim));
            o.addProperty("x", e.x);
            o.addProperty("y", e.y);
            o.addProperty("z", e.z);
            o.addProperty("action", e.type == null ? 0 : e.type.ordinal());
            o.addProperty("actor_name", safeString(e.actorName));
            o.addProperty("actor_uuid", e.actorUuid == null ? "" : e.actorUuid.toString());
            o.addProperty("data", Base64.getEncoder().encodeToString(GzipJson.toGzippedJsonBytes(e)));
            body.append(GzipJson.GSON.toJson(o)).append('\n');
        }
        execute(body.toString(), timeoutSec(), asyncInsert, waitForAsyncInsert, true);
    }


    private void insertFeedBatch(List<LogEntry> batch) throws IOException {
        if (batch == null || batch.isEmpty()) return;
        StringBuilder body = new StringBuilder(Math.max(4096, batch.size() * 384));
        body.append("INSERT INTO ").append(qualifiedFeedTable()).append(" FORMAT JSONEachRow\n");
        for (LogEntry e : batch) {
            LogIdGenerator.ensure(e);
            TableKind kind = tableFor(e);
            TargetInfo target = targetInfo(e);
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id);
            o.addProperty("ts_ms", Math.max(0L, e.ts));
            o.addProperty("dim", safeString(e.dim));
            o.addProperty("x", e.x);
            o.addProperty("y", e.y);
            o.addProperty("z", e.z);
            o.addProperty("chunk_x", floorDiv(e.x, 16));
            o.addProperty("chunk_z", floorDiv(e.z, 16));
            o.addProperty("region_x", floorDiv(e.x, 512));
            o.addProperty("region_z", floorDiv(e.z, 512));
            o.addProperty("action", e.type == null ? 0 : e.type.ordinal());
            o.addProperty("category", kind.suffix);
            o.addProperty("actor_name", safeString(e.actorName));
            o.addProperty("actor_name_lc", safeString(e.actorName).toLowerCase(Locale.ROOT));
            o.addProperty("actor_uuid", e.actorUuid == null ? "" : e.actorUuid.toString());
            o.addProperty("target_kind", target.kind);
            o.addProperty("target_id", target.id);
            o.addProperty("item_count", e.count);
            o.addProperty("source", safeString(e.source));
            o.addProperty("short_text", target.shortText);
            o.addProperty("extra", feedExtra(e));
            o.addProperty("has_details", shouldStoreDetails(e) ? 1 : 0);
            o.addProperty("source_table", kind.suffix);
            o.addProperty("source_id", e.id);
            body.append(GzipJson.GSON.toJson(o)).append('\n');
        }
        execute(body.toString(), timeoutSec(), asyncInsert, waitForAsyncInsert, true);
    }

    private boolean shouldStoreDetails(LogEntry e) {
        if (e == null) return false;
        if ("full".equals(detailMode) || "debug".equals(detailMode)) return true;
        if ("minimal".equals(detailMode)) return isRollbackCritical(e) && storeRollbackDetails;
        if (isRollbackCritical(e)) return storeRollbackDetails;
        return storeNonRollbackDetails;
    }

    private static boolean isRollbackCritical(LogEntry e) {
        if (e == null || e.type == null) return false;
        return switch (e.type) {
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_ENTITY_NBT_CHANGE,
                 CONTAINER_PUT, CONTAINER_TAKE,
                 ITEM_DROP, ITEM_PICKUP,
                 ENTITY_DEATH, ENTITY_SPAWN,
                 PLANE_PLACE, PLANE_REMOVE, PLANE_PICKUP,
                 TRAIN_ASSEMBLE, TRAIN_DISASSEMBLE -> true;
            default -> false;
        };
    }

    private static String feedExtra(LogEntry e) {
        if (e == null) return "";
        if (e.type == ActionType.CHAT_MESSAGE) return safeString(e.extra);
        if (e.extra == null || e.extra.length() <= 512) return safeString(e.extra);
        return e.extra.substring(0, 512);
    }

    private void insertSplitBatch(TableKind kind, List<LogEntry> batch) throws IOException {
        if (batch == null || batch.isEmpty()) return;
        StringBuilder body = new StringBuilder(Math.max(4096, batch.size() * 512));
        body.append("INSERT INTO ").append(qualifiedSplitTable(kind)).append(" FORMAT JSONEachRow\n");
        for (LogEntry e : batch) {
            LogIdGenerator.ensure(e);
            JsonObject o = baseJson(e);
            switch (kind) {
                case BLOCKS -> addBlock(o, e);
                case CONTAINERS -> { addBlock(o, e); addEntity(o, e, false); addItem(o, e); addContainer(o, e); }
                case ENTITIES -> { addEntity(o, e, true); addItem(o, e); }
                case ITEMS -> { addItem(o, e); addPlayerInventory(o, e); }
                case PLAYERS -> { addPlayerEntity(o, e); addPlayerInventory(o, e); }
                case CHAT -> o.addProperty("message", extractMessage(e));
                case COMPAT -> { addBlock(o, e); addEntity(o, e, true); addItem(o, e); addPlayerInventory(o, e); addContainer(o, e); }
                default -> throw new IllegalArgumentException("Unexpected table kind: " + kind);
            }
            body.append(GzipJson.GSON.toJson(o)).append('\n');
        }
        execute(body.toString(), timeoutSec(), asyncInsert, waitForAsyncInsert, true);
    }

    private JsonObject baseJson(LogEntry e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id);
        o.addProperty("ts_ms", Math.max(0L, e.ts));
        o.addProperty("dim", safeString(e.dim));
        o.addProperty("x", e.x);
        o.addProperty("y", e.y);
        o.addProperty("z", e.z);
        o.addProperty("chunk_x", floorDiv(e.x, 16));
        o.addProperty("chunk_z", floorDiv(e.z, 16));
        o.addProperty("region_x", floorDiv(e.x, 512));
        o.addProperty("region_z", floorDiv(e.z, 512));
        o.addProperty("action", e.type == null ? 0 : e.type.ordinal());
        o.addProperty("actor_name", safeString(e.actorName));
        o.addProperty("actor_name_lc", safeString(e.actorName).toLowerCase(Locale.ROOT));
        o.addProperty("actor_uuid", e.actorUuid == null ? "" : e.actorUuid.toString());
        o.addProperty("source", safeString(e.source));
        o.addProperty("extra", safeString(e.extra));
        return o;
    }

    private static void addBlock(JsonObject o, LogEntry e) {
        o.addProperty("block_before", safeString(e.blockBefore));
        o.addProperty("be_before", safeString(e.beBefore));
        o.addProperty("block_after", safeString(e.blockAfter));
        o.addProperty("be_after", safeString(e.beAfter));
    }

    private static void addEntity(JsonObject o, LogEntry e, boolean includeNbt) {
        o.addProperty("entity_type", safeString(e.entityType));
        o.addProperty("entity_uuid", e.entityUuid == null ? "" : e.entityUuid.toString());
        if (includeNbt) o.addProperty("entity_nbt", safeString(e.entityNbt));
    }

    private static void addPlayerEntity(JsonObject o, LogEntry e) {
        // avilixlogger_players intentionally has no entity_type column.
        // Do not emit entity_type here: otherwise reads and strict inserts can break on split schema.
        o.addProperty("entity_uuid", e.entityUuid == null ? "" : e.entityUuid.toString());
        o.addProperty("entity_nbt", safeString(e.entityNbt));
    }

    private static void addItem(JsonObject o, LogEntry e) {
        o.addProperty("item_stack", safeString(e.itemStackNbt));
        o.addProperty("count", e.count);
    }

    private static void addPlayerInventory(JsonObject o, LogEntry e) {
        o.addProperty("player_inv_before", safeString(e.playerInvBefore));
        o.addProperty("player_inv_after", safeString(e.playerInvAfter));
    }

    private static void addContainer(JsonObject o, LogEntry e) {
        o.addProperty("container_slots_before", safeString(e.containerSlotsBefore));
        o.addProperty("container_slots_after", safeString(e.containerSlotsAfter));
    }

    private static String extractMessage(LogEntry e) {
        return safeString(e.extra);
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
        if (!splitSchema) return selectLegacy(q, reverse);
        if (useFeedTable && !q.requireDetails) {
            List<LogEntry> feedRows = selectFeed(q, reverse);
            if (feedRows != null && !feedRows.isEmpty()) {
                ArrayList<LogEntry> merged = new ArrayList<>(feedRows);
                if (readLegacyUnifiedTable) {
                    merged.addAll(selectLegacy(q, reverse));
                    merged = dedupeById(merged);
                }
                merged.sort((a, b) -> reverse ? Long.compare(b.id, a.id) : Long.compare(a.id, b.id));
                int limit = Math.max(1, q.limit);
                if (merged.size() > limit) return new ArrayList<>(merged.subList(0, limit));
                return merged;
            }
            // Empty can be a real result, but on upgraded servers it can also mean the feed table is
            // missing/old/broken. Fall through to detail tables as a correctness fallback.
        }

        List<TableKind> kinds = tablesForQuery(q);
        ArrayList<LogEntry> out = new ArrayList<>();
        try {
            if (!kinds.isEmpty()) {
                String sql = buildSplitSelectSql(q, reverse, kinds);
                out.addAll(parseSplitJsonEachRow(execute(sql, queryTimeoutSec(q))));
            }
            if (readLegacyUnifiedTable) {
                out.addAll(selectLegacy(q, reverse));
            }
            if (!q.requireDetails && reverse && useFeedTable && (out.isEmpty() || Math.max(1, q.limit) <= 128)) {
                // Details may be disabled for non-rollback events; still return lightweight rows for GUI/details.
                // Do not use this path for large rollback scans.
                out.addAll(selectFeed(q, true));
                out = dedupeById(out);
            }
            out.sort((a, b) -> reverse ? Long.compare(b.id, a.id) : Long.compare(a.id, b.id));
            int limit = Math.max(1, q.limit);
            if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
            markHealthy();
            return out;
        } catch (Throwable e) {
            if (disableActorNameLcOnSchemaError(e) || disableSpatialHelperColumnsOnSchemaError(e)) {
                return select(q, reverse);
            }
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse split query failed", e);
            return selectSplitTablesIndividually(q, reverse, kinds);
        }
    }

    private List<LogEntry> selectSplitTablesIndividually(LogQuery q, boolean reverse, List<TableKind> kinds) {
        if (q == null || kinds == null || kinds.isEmpty()) return List.of();
        ArrayList<LogEntry> out = new ArrayList<>();
        for (TableKind kind : kinds) {
            try {
                StringBuilder sql = new StringBuilder(2048);
                sql.append(selectFromSplitTable(kind, q, reverse))
                        .append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                        .append(" LIMIT ").append(Math.max(1, q.limit))
                        .append(" FORMAT JSONEachRow");
                out.addAll(parseSplitJsonEachRow(execute(sql.toString(), queryTimeoutSec(q))));
            } catch (Throwable tableError) {
                if (disableActorNameLcOnSchemaError(tableError) || disableSpatialHelperColumnsOnSchemaError(tableError)) {
                    try {
                        StringBuilder retrySql = new StringBuilder(2048);
                        retrySql.append(selectFromSplitTable(kind, q, reverse))
                                .append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                                .append(" LIMIT ").append(Math.max(1, q.limit))
                                .append(" FORMAT JSONEachRow");
                        out.addAll(parseSplitJsonEachRow(execute(retrySql.toString(), queryTimeoutSec(q))));
                        continue;
                    } catch (Throwable retryError) {
                        tableError = retryError;
                    }
                }
                try {
                    StringBuilder minimalSql = new StringBuilder(2048);
                    minimalSql.append(selectFromSplitTableMinimal(kind, q, reverse))
                            .append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                            .append(" LIMIT ").append(Math.max(1, q.limit))
                            .append(" FORMAT JSONEachRow");
                    out.addAll(parseSplitJsonEachRow(execute(minimalSql.toString(), queryTimeoutSec(q))));
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse split table query used minimal fallback. table={}, error={}", kind.suffix, tableError.toString());
                } catch (Throwable minimalError) {
                    throw new IllegalStateException("ClickHouse could not read table " + kind.suffix
                            + "; refusing to return a partial page", minimalError);
                }
            }
        }
        if (readLegacyUnifiedTable) {
            out.addAll(selectLegacy(q, reverse));
            out = dedupeById(out);
        }
        out.sort((a, b) -> reverse ? Long.compare(b.id, a.id) : Long.compare(a.id, b.id));
        int limit = Math.max(1, q.limit);
        if (out.size() > limit) return new ArrayList<>(out.subList(0, limit));
        if (!out.isEmpty()) markHealthy();
        return out;
    }


    private List<LogEntry> selectFeed(LogQuery q, boolean reverse) {
        StringBuilder sql = new StringBuilder(2048);
        sql.append("SELECT id, ts_ms, dim, x, y, z, action, actor_name, actor_uuid, target_kind, target_id, item_count, source, short_text, extra, has_details ")
                .append("FROM ").append(qualifiedFeedTable());
        appendFeedWhere(sql, q, reverse);
        sql.append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                .append(" LIMIT ").append(Math.max(1, q.limit))
                .append(" FORMAT JSONEachRow");
        try {
            List<LogEntry> rows = parseFeedJsonEachRow(execute(sql.toString(), queryTimeoutSec(q)));
            markHealthy();
            return rows;
        } catch (Throwable e) {
            if (disableActorNameLcOnSchemaError(e) || disableSpatialHelperColumnsOnSchemaError(e)) {
                return selectFeed(q, reverse);
            }
            List<LogEntry> fallback = selectFeedMinimal(q, reverse, e);
            if (fallback != null) return fallback;
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse feed query failed", e);
            throw new IllegalStateException("ClickHouse feed query failed; refusing to return a partial page", e);
        }
    }

    private List<LogEntry> selectFeedMinimal(LogQuery q, boolean reverse, Throwable original) {
        try {
            StringBuilder sql = new StringBuilder(1536);
            sql.append("SELECT id, ts_ms, dim, x, y, z, action, actor_name, actor_uuid, ")
                    .append("'' AS target_kind, '' AS target_id, 0 AS item_count, '' AS source, '' AS short_text, '' AS extra, 0 AS has_details ")
                    .append("FROM ").append(qualifiedFeedTable());
            appendFeedWhere(sql, q, reverse, false);
            sql.append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                    .append(" LIMIT ").append(Math.max(1, q.limit))
                    .append(" FORMAT JSONEachRow");
            List<LogEntry> rows = parseFeedJsonEachRow(execute(sql.toString(), queryTimeoutSec(q)));
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse feed query used minimal fallback after schema/read error: {}", original == null ? "unknown" : original.toString());
            markHealthy();
            return rows;
        } catch (Throwable fallbackError) {
            if (disableActorNameLcOnSchemaError(fallbackError) || disableSpatialHelperColumnsOnSchemaError(fallbackError)) {
                return selectFeedMinimal(q, reverse, original);
            }
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse feed minimal fallback failed: {}", fallbackError.toString());
            return null;
        }
    }

    private boolean disableActorNameLcOnSchemaError(Throwable t) {
        if (!useActorNameLcFilter || t == null) return false;
        String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("actor_name_lc")) {
            useActorNameLcFilter = false;
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse actor_name_lc is unavailable; falling back to lowerUTF8(actor_name) filters.");
            return true;
        }
        return false;
    }


    private boolean disableSpatialHelperColumnsOnSchemaError(Throwable t) {
        if (!useSpatialHelperColumns || t == null) return false;
        String msg = String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT);
        if (msg.contains("region_x") || msg.contains("region_z") || msg.contains("chunk_x") || msg.contains("chunk_z")) {
            useSpatialHelperColumns = false;
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse spatial helper columns are unavailable; falling back to x/y/z-only position filters.");
            return true;
        }
        return false;
    }

    private String buildSplitSelectSql(LogQuery q, boolean reverse, List<TableKind> kinds) {
        StringBuilder sql = new StringBuilder(4096);
        sql.append("SELECT * FROM (");
        for (int i = 0; i < kinds.size(); i++) {
            if (i > 0) sql.append(" UNION ALL ");
            sql.append("(")
                    .append(selectFromSplitTable(kinds.get(i), q, reverse))
                    .append(" ORDER BY id ").append(reverse ? "DESC" : "ASC")
                    .append(" LIMIT ").append(Math.max(1, q.limit))
                    .append(")");
        }
        sql.append(") ORDER BY id ").append(reverse ? "DESC" : "ASC")
                .append(" LIMIT ").append(Math.max(1, q.limit))
                .append(" FORMAT JSONEachRow");
        return sql.toString();
    }

    private String selectFromSplitTable(TableKind kind, LogQuery q, boolean reverse) {
        StringBuilder sql = new StringBuilder(1024);
        sql.append("SELECT ")
                .append("id, ts_ms, dim, x, y, z, action, actor_name, actor_uuid, source, ")
                .append(extraSelectExpr(kind)).append(" AS extra, ")
                .append(selectExpr(kind, "block_before")).append(" AS block_before, ")
                .append(selectExpr(kind, "be_before")).append(" AS be_before, ")
                .append(selectExpr(kind, "block_after")).append(" AS block_after, ")
                .append(selectExpr(kind, "be_after")).append(" AS be_after, ")
                .append(selectExpr(kind, "entity_type")).append(" AS entity_type, ")
                .append(selectExpr(kind, "entity_uuid")).append(" AS entity_uuid, ")
                .append(selectExpr(kind, "entity_nbt")).append(" AS entity_nbt, ")
                .append(selectExpr(kind, "item_stack")).append(" AS item_stack, ")
                .append(selectExpr(kind, "count")).append(" AS count, ")
                .append(selectExpr(kind, "player_inv_before")).append(" AS player_inv_before, ")
                .append(selectExpr(kind, "player_inv_after")).append(" AS player_inv_after, ")
                .append(selectExpr(kind, "container_slots_before")).append(" AS container_slots_before, ")
                .append(selectExpr(kind, "container_slots_after")).append(" AS container_slots_after ")
                .append("FROM ").append(qualifiedSplitTable(kind));
        appendWhere(sql, q, reverse, kind);
        return sql.toString();
    }

    private String selectFromSplitTableMinimal(TableKind kind, LogQuery q, boolean reverse) {
        StringBuilder sql = new StringBuilder(768);
        sql.append("SELECT ")
                .append("id, ts_ms, dim, x, y, z, action, actor_name, actor_uuid, '' AS source, ")
                .append("'' AS extra, ")
                .append("'' AS block_before, '' AS be_before, '' AS block_after, '' AS be_after, ")
                .append("'' AS entity_type, '' AS entity_uuid, '' AS entity_nbt, ")
                .append("'' AS item_stack, 0 AS count, ")
                .append("'' AS player_inv_before, '' AS player_inv_after, ")
                .append("'' AS container_slots_before, '' AS container_slots_after ")
                .append("FROM ").append(qualifiedSplitTable(kind));
        appendWhere(sql, q, reverse, kind, false);
        return sql.toString();
    }

    private String extraSelectExpr(TableKind kind) {
        if (kind == TableKind.CHAT) {
            // Some upgraded tables have historical chat text in `message` while `extra` was added later.
            return "if(empty(extra), message, extra)";
        }
        return "extra";
    }

    private String selectExpr(TableKind kind, String column) {
        boolean has = switch (column) {
            case "block_before", "be_before", "block_after", "be_after" -> kind == TableKind.BLOCKS || kind == TableKind.CONTAINERS || kind == TableKind.COMPAT;
            case "entity_type" -> kind == TableKind.CONTAINERS || kind == TableKind.ENTITIES || kind == TableKind.COMPAT;
            case "entity_uuid" -> kind == TableKind.CONTAINERS || kind == TableKind.ENTITIES || kind == TableKind.PLAYERS || kind == TableKind.COMPAT;
            case "entity_nbt" -> kind == TableKind.ENTITIES || kind == TableKind.PLAYERS || kind == TableKind.COMPAT;
            case "item_stack", "count" -> kind == TableKind.CONTAINERS || kind == TableKind.ENTITIES || kind == TableKind.ITEMS || kind == TableKind.COMPAT;
            case "player_inv_before", "player_inv_after" -> kind == TableKind.ITEMS || kind == TableKind.PLAYERS || kind == TableKind.COMPAT;
            case "container_slots_before", "container_slots_after" -> kind == TableKind.CONTAINERS || kind == TableKind.COMPAT;
            default -> false;
        };
        if (!has) return "count".equals(column) ? "0" : "''";
        return column;
    }


    private void appendFeedWhere(StringBuilder sql, LogQuery q, boolean reverse) {
        appendFeedWhere(sql, q, reverse, true);
    }

    private void appendFeedWhere(StringBuilder sql, LogQuery q, boolean reverse, boolean includeOptionalFilters) {
        sql.append(" WHERE ts_ms >= ").append(Math.max(0L, q.sinceTs))
                .append(" AND ts_ms <= ").append(Math.max(0L, q.untilTs));

        boolean allDims = isAllDimensions(q.dim);
        if (!allDims) sql.append(" AND dim = ").append(sqlString(q.dim));

        if (q.types != null && !q.types.isEmpty()) {
            sql.append(" AND action IN (");
            int i = 0;
            for (ActionType t : q.types) {
                if (i++ > 0) sql.append(',');
                sql.append(t.ordinal());
            }
            sql.append(')');
        } else if (q.type != null) {
            sql.append(" AND action = ").append(q.type.ordinal());
        }

        if (q.actorName != null && !q.actorName.isBlank()) {
            appendActorWhere(sql, q.actorName);
        }

        appendPositionWhere(sql, q);
        if (includeOptionalFilters) appendFeedOptionalFilters(sql, q);

        if (reverse) {
            if (q.beforeId > 0) sql.append(" AND id < ").append(q.beforeId);
        } else {
            if (q.afterId > 0) sql.append(" AND id > ").append(q.afterId);
        }
    }

    private void appendWhere(StringBuilder sql, LogQuery q, boolean reverse, TableKind kind) {
        appendWhere(sql, q, reverse, kind, true);
    }

    private void appendWhere(StringBuilder sql, LogQuery q, boolean reverse, TableKind kind, boolean includeOptionalFilters) {
        sql.append(" WHERE ts_ms >= ").append(Math.max(0L, q.sinceTs))
                .append(" AND ts_ms <= ").append(Math.max(0L, q.untilTs));

        boolean allDims = isAllDimensions(q.dim);
        if (!allDims) sql.append(" AND dim = ").append(sqlString(q.dim));

        if (q.types != null && !q.types.isEmpty()) {
            sql.append(" AND action IN (");
            int i = 0;
            for (ActionType t : q.types) {
                if (i++ > 0) sql.append(',');
                sql.append(t.ordinal());
            }
            sql.append(')');
        } else if (q.type != null) {
            sql.append(" AND action = ").append(q.type.ordinal());
        }

        if (q.actorName != null && !q.actorName.isBlank()) {
            appendActorWhere(sql, q.actorName);
        }

        appendPositionWhere(sql, q);
        if (includeOptionalFilters) appendSplitOptionalFilters(sql, q, kind);

        if (reverse) {
            if (q.beforeId > 0) sql.append(" AND id < ").append(q.beforeId);
        } else {
            if (q.afterId > 0) sql.append(" AND id > ").append(q.afterId);
        }
    }


    private void appendFeedOptionalFilters(StringBuilder sql, LogQuery q) {
        if (q == null) return;
        String block = normalizeSqlNeedle(q.blockIdFilter);
        if (!block.isBlank()) {
            appendInsensitiveContainsAny(sql, block, "target_id", "short_text", "extra");
        }
        String planeName = normalizeSqlNeedle(q.planeNameFilter);
        if (!planeName.isBlank()) {
            appendInsensitiveContainsAny(sql, planeName, "target_id", "short_text", "extra");
        }
        String text = normalizeSqlNeedle(q.extraTextFilter);
        if (!text.isBlank()) {
            appendInsensitiveContainsAny(sql, text, "target_id", "short_text", "extra", "source");
        }
    }

    private void appendSplitOptionalFilters(StringBuilder sql, LogQuery q, TableKind kind) {
        if (q == null || kind == null) return;
        String block = normalizeSqlNeedle(q.blockIdFilter);
        if (!block.isBlank()) {
            switch (kind) {
                case BLOCKS, CONTAINERS, COMPAT -> appendInsensitiveContainsAny(sql, block, "block_before", "block_after", "extra", "source");
                default -> appendInsensitiveContainsAny(sql, block, "extra", "source");
            }
        }

        String planeName = normalizeSqlNeedle(q.planeNameFilter);
        if (!planeName.isBlank()) {
            switch (kind) {
                case ENTITIES, COMPAT -> appendInsensitiveContainsAny(sql, planeName, "entity_type", "entity_nbt", "extra", "source");
                default -> appendInsensitiveContainsAny(sql, planeName, "extra", "source");
            }
        }

        String text = normalizeSqlNeedle(q.extraTextFilter);
        if (!text.isBlank()) {
            appendInsensitiveContainsAny(sql, text, "extra", "source");
        }
    }

    private static void appendInsensitiveContainsAny(StringBuilder sql, String needle, String... columns) {
        if (sql == null || needle == null || needle.isBlank() || columns == null || columns.length == 0) return;
        sql.append(" AND (");
        int n = 0;
        String lit = sqlString(needle.toLowerCase(Locale.ROOT));
        for (String column : columns) {
            if (column == null || column.isBlank()) continue;
            if (n++ > 0) sql.append(" OR ");
            sql.append("positionCaseInsensitiveUTF8(").append(column).append(", ").append(lit).append(") > 0");
        }
        if (n == 0) sql.append("1");
        sql.append(')');
    }

    private static String normalizeSqlNeedle(String s) {
        if (s == null) return "";
        String out = s.trim().toLowerCase(Locale.ROOT);
        while (!out.isEmpty()) {
            char c0 = out.charAt(0);
            if (c0 == '\'' || c0 == '"' || c0 == '`' || Character.isWhitespace(c0)) out = out.substring(1).trim();
            else break;
        }
        while (!out.isEmpty()) {
            char c1 = out.charAt(out.length() - 1);
            if (c1 == '\'' || c1 == '"' || c1 == '`' || Character.isWhitespace(c1)) out = out.substring(0, out.length() - 1).trim();
            else break;
        }
        return out;
    }

    private void appendActorWhere(StringBuilder sql, String actorName) {
        String normalized = safeString(actorName).toLowerCase(Locale.ROOT);
        if (useActorNameLcFilter) {
            sql.append(" AND actor_name_lc = ").append(sqlString(normalized));
        } else {
            sql.append(" AND lowerUTF8(actor_name) = ").append(sqlString(normalized));
        }
    }


    private void appendPositionWhere(StringBuilder sql, LogQuery q) {
        if (q.exactPos != null) {
            int x = q.exactPos.getX();
            int y = q.exactPos.getY();
            int z = q.exactPos.getZ();
            if (useSpatialHelperColumns) {
                sql.append(" AND region_x = ").append(floorDiv(x, 512))
                        .append(" AND region_z = ").append(floorDiv(z, 512))
                        .append(" AND chunk_x = ").append(floorDiv(x, 16))
                        .append(" AND chunk_z = ").append(floorDiv(z, 16));
            }
            sql.append(" AND x = ").append(x)
                    .append(" AND y = ").append(y)
                    .append(" AND z = ").append(z);
        } else if (q.minPos != null && q.maxPos != null) {
            BlockPos min = q.minPos;
            BlockPos max = q.maxPos;
            int minX = Math.min(min.getX(), max.getX());
            int maxX = Math.max(min.getX(), max.getX());
            int minY = Math.min(min.getY(), max.getY());
            int maxY = Math.max(min.getY(), max.getY());
            int minZ = Math.min(min.getZ(), max.getZ());
            int maxZ = Math.max(min.getZ(), max.getZ());
            if (useSpatialHelperColumns) {
                sql.append(" AND region_x BETWEEN ").append(floorDiv(minX, 512)).append(" AND ").append(floorDiv(maxX, 512))
                        .append(" AND region_z BETWEEN ").append(floorDiv(minZ, 512)).append(" AND ").append(floorDiv(maxZ, 512))
                        .append(" AND chunk_x BETWEEN ").append(floorDiv(minX, 16)).append(" AND ").append(floorDiv(maxX, 16))
                        .append(" AND chunk_z BETWEEN ").append(floorDiv(minZ, 16)).append(" AND ").append(floorDiv(maxZ, 16));
            }
            sql.append(" AND x BETWEEN ").append(minX).append(" AND ").append(maxX)
                    .append(" AND y BETWEEN ").append(minY).append(" AND ").append(maxY)
                    .append(" AND z BETWEEN ").append(minZ).append(" AND ").append(maxZ);
        }
    }

    private List<LogEntry> selectLegacy(LogQuery q, boolean reverse) {
        StringBuilder sql = new StringBuilder();
        boolean allDims = isAllDimensions(q.dim);
        sql.append("SELECT id, data FROM ").append(qualifiedLegacyTable())
                .append(" WHERE ts_ms >= ").append(Math.max(0L, q.sinceTs))
                .append(" AND ts_ms <= ").append(Math.max(0L, q.untilTs));

        if (!allDims) sql.append(" AND dim = ").append(sqlString(q.dim));

        if (q.types != null && !q.types.isEmpty()) {
            sql.append(" AND action IN (");
            int i = 0;
            for (ActionType t : q.types) {
                if (i++ > 0) sql.append(',');
                sql.append(t.ordinal());
            }
            sql.append(')');
        } else if (q.type != null) {
            sql.append(" AND action = ").append(q.type.ordinal());
        }

        if (q.actorName != null && !q.actorName.isBlank()) {
            sql.append(" AND lowerUTF8(actor_name) = ").append(sqlString(q.actorName.toLowerCase(Locale.ROOT)));
        }

        if (q.exactPos != null) {
            sql.append(" AND x = ").append(q.exactPos.getX())
                    .append(" AND y = ").append(q.exactPos.getY())
                    .append(" AND z = ").append(q.exactPos.getZ());
        } else if (q.minPos != null && q.maxPos != null) {
            BlockPos min = q.minPos;
            BlockPos max = q.maxPos;
            sql.append(" AND x BETWEEN ").append(Math.min(min.getX(), max.getX())).append(" AND ").append(Math.max(min.getX(), max.getX()))
                    .append(" AND y BETWEEN ").append(Math.min(min.getY(), max.getY())).append(" AND ").append(Math.max(min.getY(), max.getY()))
                    .append(" AND z BETWEEN ").append(Math.min(min.getZ(), max.getZ())).append(" AND ").append(Math.max(min.getZ(), max.getZ()));
        }

        if (reverse) {
            if (q.beforeId > 0) sql.append(" AND id < ").append(q.beforeId);
            sql.append(" ORDER BY id DESC");
        } else {
            if (q.afterId > 0) sql.append(" AND id > ").append(q.afterId);
            sql.append(" ORDER BY id ASC");
        }
        sql.append(" LIMIT ").append(Math.max(1, q.limit));
        sql.append(" FORMAT TabSeparated");

        List<LogEntry> out = new ArrayList<>();
        try {
            String response = execute(sql.toString(), queryTimeoutSec(q));
            if (response == null || response.isBlank()) {
                markHealthy();
                return out;
            }
            String[] lines = response.split("\\R");
            for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
                String line = lines[lineNumber];
                if (line == null || line.isBlank()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) throw new IllegalStateException("Invalid unified ClickHouse row at response line " + (lineNumber + 1));
                long id;
                try { id = Long.parseLong(line.substring(0, tab).trim()); }
                catch (NumberFormatException invalidId) {
                    throw new IllegalStateException("Invalid unified ClickHouse id at response line " + (lineNumber + 1), invalidId);
                }
                String data = line.substring(tab + 1).trim();
                if (data.isBlank()) throw new IllegalStateException("Missing unified ClickHouse payload at response line " + (lineNumber + 1));
                byte[] gz = Base64.getDecoder().decode(data);
                LogEntry e = GzipJson.fromGzippedJsonBytes(gz, LogEntry.class);
                if (e == null) throw new IllegalStateException("Empty unified ClickHouse payload at response line " + (lineNumber + 1));
                e.id = id;
                out.add(e);
            }
            markHealthy();
        } catch (Throwable e) {
            throw new IllegalStateException("ClickHouse unified-table query failed; refusing to return an empty/partial page", e);
        }
        return out;
    }



    private static ArrayList<LogEntry> dedupeById(List<LogEntry> in) {
        LinkedHashMap<Long, LogEntry> byId = new LinkedHashMap<>();
        if (in != null) {
            for (LogEntry e : in) {
                if (e == null) continue;
                LogEntry prev = byId.get(e.id);
                if (prev == null || isLightweight(prev) && !isLightweight(e)) {
                    byId.put(e.id, e);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    private static boolean isLightweight(LogEntry e) {
        if (e == null) return true;
        return isBlank(e.beBefore) && isBlank(e.beAfter)
                && isBlank(e.containerSlotsBefore) && isBlank(e.containerSlotsAfter)
                && isBlank(e.entityNbt) && isBlank(e.playerInvBefore) && isBlank(e.playerInvAfter);
    }

    private List<LogEntry> parseFeedJsonEachRow(String response) {
        if (response == null || response.isBlank()) return List.of();
        ArrayList<LogEntry> out = new ArrayList<>();
        String[] lines = response.split("\\R");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            if (line == null || line.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                LogEntry e = new LogEntry();
                e.id = getRequiredLong(o, "id");
                e.ts = getRequiredLong(o, "ts_ms");
                e.dim = nullIfBlank(getString(o, "dim"));
                int action = (int) getRequiredLong(o, "action");
                ActionType[] values = ActionType.values();
                e.type = action >= 0 && action < values.length ? values[action] : null;
                e.actorName = nullIfBlank(getString(o, "actor_name"));
                e.actorUuid = parseUuid(getString(o, "actor_uuid"));
                e.source = nullIfBlank(getString(o, "source"));
                e.x = (int) getRequiredLong(o, "x");
                e.y = (int) getRequiredLong(o, "y");
                e.z = (int) getRequiredLong(o, "z");
                String targetKind = getString(o, "target_kind");
                String targetId = getString(o, "target_id");
                String shortText = getString(o, "short_text");
                e.count = (int) getLong(o, "item_count");
                hydrateFeedTarget(e, targetKind, targetId, shortText);
                e.extra = nullIfBlank(getString(o, "extra"));
                if (e.type == ActionType.CHAT_MESSAGE && e.extra == null) e.extra = shortText;
                out.add(e);
            } catch (Throwable parseError) {
                throw new IllegalStateException("Invalid ClickHouse feed row at response line " + (lineNumber + 1), parseError);
            }
        }
        return out;
    }

    private static void hydrateFeedTarget(LogEntry e, String targetKind, String targetId, String shortText) {
        String kind = safeString(targetKind);
        String id = safeString(targetId);
        if ("block".equals(kind)) {
            if (e.type == ActionType.BLOCK_BREAK) e.blockBefore = id;
            else e.blockAfter = id;
        } else if ("item".equals(kind)) {
            e.itemStackNbt = minimalItemSnbt(id, e.count);
        } else if ("entity".equals(kind)) {
            e.entityType = id;
        } else if ("train".equals(kind)) {
            e.extra = "{\"trainName\":\"" + id.replace("\"", "") + "\"}";
        } else if ("chat".equals(kind)) {
            e.extra = shortText;
        }
    }

    private List<LogEntry> parseSplitJsonEachRow(String response) {
        if (response == null || response.isBlank()) return List.of();
        ArrayList<LogEntry> out = new ArrayList<>();
        String[] lines = response.split("\\R");
        for (int lineNumber = 0; lineNumber < lines.length; lineNumber++) {
            String line = lines[lineNumber];
            if (line == null || line.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                out.add(fromJson(o));
            } catch (Throwable parseError) {
                throw new IllegalStateException("Invalid ClickHouse detail row at response line " + (lineNumber + 1), parseError);
            }
        }
        return out;
    }

    private LogEntry fromJson(JsonObject o) {
        LogEntry e = new LogEntry();
        e.id = getRequiredLong(o, "id");
        e.ts = getRequiredLong(o, "ts_ms");
        e.dim = nullIfBlank(getString(o, "dim"));
        int action = (int) getRequiredLong(o, "action");
        ActionType[] values = ActionType.values();
        e.type = action >= 0 && action < values.length ? values[action] : null;
        e.actorName = nullIfBlank(getString(o, "actor_name"));
        e.actorUuid = parseUuid(getString(o, "actor_uuid"));
        e.source = nullIfBlank(getString(o, "source"));
        e.x = (int) getRequiredLong(o, "x");
        e.y = (int) getRequiredLong(o, "y");
        e.z = (int) getRequiredLong(o, "z");
        e.blockBefore = nullIfBlank(getString(o, "block_before"));
        e.beBefore = nullIfBlank(getString(o, "be_before"));
        e.blockAfter = nullIfBlank(getString(o, "block_after"));
        e.beAfter = nullIfBlank(getString(o, "be_after"));
        e.entityType = nullIfBlank(getString(o, "entity_type"));
        e.entityUuid = parseUuid(getString(o, "entity_uuid"));
        e.entityNbt = nullIfBlank(getString(o, "entity_nbt"));
        e.itemStackNbt = nullIfBlank(getString(o, "item_stack"));
        e.count = (int) getLong(o, "count");
        e.playerInvBefore = nullIfBlank(getString(o, "player_inv_before"));
        e.playerInvAfter = nullIfBlank(getString(o, "player_inv_after"));
        e.containerSlotsBefore = nullIfBlank(getString(o, "container_slots_before"));
        e.containerSlotsAfter = nullIfBlank(getString(o, "container_slots_after"));
        e.extra = nullIfBlank(getString(o, "extra"));
        return e;
    }

    @Override
    public void shutdown() {
        running = false;
        writer.interrupt();
        try { writer.join(5_000L); } catch (InterruptedException ignored) {}
        if (writer.isAlive()) AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse writer thread did not stop within timeout.");
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] ClickHouse HTTP storage shutdown. written={}, dropped={}", written, dropped);
    }

    private String execute(String sql, int timeoutSec) throws IOException {
        return execute(sql, timeoutSec, false, true, false);
    }

    private String execute(String sql, int timeoutSec, boolean async, boolean waitForAsync, boolean insert) throws IOException {
        String query = "user=" + urlEncode(user)
                + "&password=" + urlEncode(password)
                + "&max_execution_time=" + Math.max(1, timeoutSec)
                + "&input_format_skip_unknown_fields=1";
        if (async && insert) {
            query += "&async_insert=1&wait_for_async_insert=" + (waitForAsync ? "1" : "0");
        }
        URL url = URI.create(endpoint + (endpoint.contains("?") ? "&" : "?") + query).toURL();
        byte[] body = sql.getBytes(StandardCharsets.UTF_8);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5_000);
        conn.setReadTimeout(Math.max(1, timeoutSec) * 1000);
        conn.setRequestProperty("Content-Type", "text/plain; charset=UTF-8");
        conn.setRequestProperty("Accept", "text/plain");
        conn.setFixedLengthStreamingMode(body.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(body);
        }

        int code = conn.getResponseCode();
        byte[] bytes;
        if (code >= 200 && code < 300) {
            try (var in = conn.getInputStream()) { bytes = in.readAllBytes(); }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        try (var err = conn.getErrorStream()) {
            bytes = err == null ? new byte[0] : err.readAllBytes();
        }
        throw new IOException("ClickHouse HTTP " + code + ": " + new String(bytes, StandardCharsets.UTF_8));
    }

    private List<TableKind> tablesForQuery(LogQuery q) {
        EnumSet<TableKind> kinds = EnumSet.noneOf(TableKind.class);
        if (q.types != null && !q.types.isEmpty()) {
            for (ActionType t : q.types) kinds.add(tableFor(t));
        } else if (q.type != null) {
            kinds.add(tableFor(q.type));
        } else {
            kinds.addAll(splitKinds());
        }
        kinds.remove(TableKind.LEGACY);
        return new ArrayList<>(kinds);
    }

    private static TableKind tableFor(LogEntry e) {
        return tableFor(e == null ? null : e.type);
    }

    private static TableKind tableFor(ActionType type) {
        if (type == null) return TableKind.COMPAT;
        return ACTION_TABLE.getOrDefault(type, TableKind.COMPAT);
    }

    private static EnumSet<TableKind> splitKinds() {
        return EnumSet.of(TableKind.BLOCKS, TableKind.CONTAINERS, TableKind.ENTITIES, TableKind.ITEMS, TableKind.PLAYERS, TableKind.CHAT, TableKind.COMPAT);
    }


    private void ensureSkippingIndexes() {
        ArrayList<String> tables = new ArrayList<>();
        if (useFeedTable) tables.add(qualifiedFeedTable());
        for (TableKind kind : splitKinds()) tables.add(qualifiedSplitTable(kind));

        for (String table : tables) {
            tryExecute("ALTER TABLE " + table + " ADD INDEX IF NOT EXISTS idx_ts_ms ts_ms TYPE minmax GRANULARITY 1");
            tryExecute("ALTER TABLE " + table + " ADD INDEX IF NOT EXISTS idx_id id TYPE minmax GRANULARITY 1");
            tryExecute("ALTER TABLE " + table + " ADD INDEX IF NOT EXISTS idx_actor_uuid actor_uuid TYPE bloom_filter(0.01) GRANULARITY 4");
            tryExecute("ALTER TABLE " + table + " ADD INDEX IF NOT EXISTS idx_actor_name_lc actor_name_lc TYPE bloom_filter(0.01) GRANULARITY 4");
            tryExecute("ALTER TABLE " + table + " ADD INDEX IF NOT EXISTS idx_source source TYPE set(1024) GRANULARITY 4");
        }

        if (useFeedTable) {
            tryExecute("ALTER TABLE " + qualifiedFeedTable() + " ADD INDEX IF NOT EXISTS idx_target_id target_id TYPE bloom_filter(0.01) GRANULARITY 4");
            tryExecute("ALTER TABLE " + qualifiedFeedTable() + " ADD INDEX IF NOT EXISTS idx_category category TYPE set(64) GRANULARITY 4");
        }

        tryExecute("ALTER TABLE " + qualifiedSplitTable(TableKind.BLOCKS) + " ADD INDEX IF NOT EXISTS idx_block_after block_after TYPE bloom_filter(0.01) GRANULARITY 4");
        tryExecute("ALTER TABLE " + qualifiedSplitTable(TableKind.CONTAINERS) + " ADD INDEX IF NOT EXISTS idx_item_stack item_stack TYPE bloom_filter(0.01) GRANULARITY 4");
        tryExecute("ALTER TABLE " + qualifiedSplitTable(TableKind.ENTITIES) + " ADD INDEX IF NOT EXISTS idx_entity_type entity_type TYPE set(1024) GRANULARITY 4");
        tryExecute("ALTER TABLE " + qualifiedSplitTable(TableKind.ITEMS) + " ADD INDEX IF NOT EXISTS idx_item_stack item_stack TYPE bloom_filter(0.01) GRANULARITY 4");
        tryExecute("ALTER TABLE " + qualifiedSplitTable(TableKind.COMPAT) + " ADD INDEX IF NOT EXISTS idx_entity_type entity_type TYPE set(1024) GRANULARITY 4");
    }

    private void tryExecute(String sql) {
        try {
            execute(sql, timeoutSec());
        } catch (Throwable ignored) {
            // ClickHouse versions differ in index DDL support. Missing index must not disable logging.
        }
    }

    private String qualifiedLegacyTable() {
        return quoteIdent(database) + "." + quoteIdent(legacyTable);
    }

    private String qualifiedFeedTable() {
        return quoteIdent(database) + "." + quoteIdent(tablePrefix + "_feed");
    }

    private String qualifiedSplitTable(TableKind kind) {
        return quoteIdent(database) + "." + quoteIdent(tablePrefix + "_" + kind.suffix);
    }

    private static int timeoutSec() {
        try { return Math.max(1, LoggerConfig.VALUES.clickHouseSelectQueryTimeoutSec.get()); }
        catch (Throwable ignored) { return 15; }
    }

    private static boolean isAllDimensions(String dim) {
        return dim == null || dim.isBlank() || "*".equals(dim);
    }

    private static boolean isLongOrGlobalQuery(LogQuery q) {
        if (q == null) return false;
        boolean wholeDimension = q.exactPos == null && q.minPos == null && q.maxPos == null;
        boolean interactiveWholeWorld = wholeDimension && q.debugSource != null
                && (q.debugSource.startsWith("gui") || q.debugSource.startsWith("command"));
        return isAllDimensions(q.dim)
                || interactiveWholeWorld
                || (q.untilTs > q.sinceTs && q.untilTs - q.sinceTs >= 30L * 24L * 60L * 60_000L)
                || (q.debugSource != null && q.debugSource.startsWith("rollback"));
    }

    private static int queryTimeoutSec(LogQuery q) {
        if (isLongOrGlobalQuery(q)) {
            try { return Math.max(timeoutSec(), LoggerConfig.VALUES.clickHouseWorldQueryTimeoutSec.get()); }
            catch (Throwable ignored) {}
        }
        return timeoutSec();
    }

    private static String normalizeHttpEndpoint(String configured) {
        String s = configured == null ? "" : configured.trim();
        if (s.startsWith("jdbc:clickhouse:")) s = s.substring("jdbc:clickhouse:".length());
        if (s.startsWith("http://") || s.startsWith("https://")) {
            URI uri = URI.create(s);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || host.isBlank()) throw new IllegalArgumentException("Invalid ClickHouse HTTP URL: " + configured);
            return scheme + "://" + host + (port >= 0 ? ":" + port : "") + "/";
        }
        if (!s.isBlank()) return "http://" + s.replaceAll("/.*$", "") + "/";
        return "http://127.0.0.1:8123/";
    }

    private static String sanitizePrefix(String prefix, String fallbackTable) {
        String s = safeString(prefix).trim();
        if (s.isBlank()) {
            s = safeString(fallbackTable).trim();
            if (s.endsWith("_actions")) s = s.substring(0, s.length() - "_actions".length());
            if (s.isBlank()) s = "avilixlogger";
        }
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe ClickHouse table prefix: " + prefix);
        return s;
    }

    private static String quoteIdent(String value) {
        String s = value == null ? "" : value.trim();
        if (!s.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Unsafe ClickHouse identifier: " + value);
        return "`" + s + "`";
    }

    private static String sqlString(String s) {
        if (s == null) return "''";
        StringBuilder out = new StringBuilder(s.length() + 8);
        out.append('\'');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '\'' -> out.append("\\'");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\0' -> out.append("\\0");
                default -> out.append(c);
            }
        }
        out.append('\'');
        return out.toString();
    }


    private record TargetInfo(String kind, String id, String shortText) {}

    private static final Pattern BLOCK_NAME_QUOTED = Pattern.compile("Name\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern BLOCK_NAME_BARE = Pattern.compile("Name\\s*:\\s*([a-z0-9_\\-\\.]+:[a-z0-9_\\-\\.]+)");
    private static final Pattern ITEM_ID_QUOTED = Pattern.compile("id\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ITEM_ID_BARE = Pattern.compile("id\\s*:\\s*([a-z0-9_\\-\\.]+:[a-z0-9_\\-\\.]+)");

    private static TargetInfo targetInfo(LogEntry e) {
        if (e == null || e.type == null) return new TargetInfo("unknown", "", "");
        return switch (e.type) {
            case BLOCK_BREAK -> targetBlock(e.blockBefore, e.extra);
            case BLOCK_PLACE, BLOCK_INTERACT, BLOCK_USE, BLOCK_ENTITY_NBT_CHANGE, CONTAINER_OPEN -> targetBlock(e.blockAfter, e.extra);
            case CONTAINER_PUT, CONTAINER_TAKE, ITEM_PICKUP, ITEM_DROP, ITEM_CRAFT, ITEM_SMELT,
                 ITEM_USE, ITEM_USE_START, ITEM_USE_STOP, ITEM_CONSUME, PLANE_PICKUP, TRAIN_SCHEDULE_TAKE, TRAIN_SCHEDULE_PUT -> targetItem(e.itemStackNbt, e.count, e.extra);
            case ENTITY_DEATH, ENTITY_SPAWN, ENTITY_MOUNT, ENTITY_DISMOUNT, ENTITY_CONTAINER_OPEN,
                 ENTITY_INTERACT, ENTITY_ATTACK, PROJECTILE_SHOOT, PROJECTILE_HIT, ENTITY_OWNER_SET, PLANE_PLACE, PLANE_REMOVE, PLANE_MOUNT ->
                    new TargetInfo("entity", safeString(e.entityType), safeString(e.entityType));
            case CHAT_MESSAGE -> new TargetInfo("chat", "", safeString(e.extra));
            case TRAIN_ASSEMBLE, TRAIN_DISASSEMBLE, TRAIN_CONTROL_START, TRAIN_CONTROL_STOP ->
                    new TargetInfo("train", extractExtraName(e.extra, "trainName", "поезд"), safeString(e.extra));
            case PLAYER_DEATH, PLAYER_JOIN, PLAYER_LEAVE, PLAYER_DIMENSION_CHANGE, PLAYER_RESPAWN, GUI_OPEN ->
                    new TargetInfo("player", safeString(e.actorName), safeString(e.actorName));
            default -> new TargetInfo("other", "", safeString(e.extra));
        };
    }

    private static TargetInfo targetBlock(String snbt, String fallback) {
        String id = extractBlockId(snbt);
        if (id == null || id.isBlank()) id = extractBlockId(fallback);
        if (id == null) id = "";
        return new TargetInfo("block", id, id);
    }

    private static TargetInfo targetItem(String snbt, int count, String fallback) {
        String id = extractItemId(snbt);
        if (id == null || id.isBlank()) id = extractItemId(fallback);
        if (id == null) id = "";
        return new TargetInfo("item", id, id + (count > 0 ? " x" + count : ""));
    }

    private static String extractBlockId(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        if (!trimmed.startsWith("{") && trimmed.contains(":") && !trimmed.contains(" ")) return trimmed;
        var m = BLOCK_NAME_QUOTED.matcher(trimmed);
        if (m.find()) return m.group(1);
        m = BLOCK_NAME_BARE.matcher(trimmed);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String extractItemId(String s) {
        if (s == null || s.isBlank()) return null;
        var m = ITEM_ID_QUOTED.matcher(s);
        if (m.find()) return m.group(1);
        m = ITEM_ID_BARE.matcher(s);
        if (m.find()) return m.group(1);
        return null;
    }

    private static String minimalItemSnbt(String id, int count) {
        if (id == null || id.isBlank()) return "";
        int c = Math.max(1, count);
        return "{id:\"" + id.replace("\"", "") + "\",Count:" + c + "b}";
    }

    private static String extractExtraName(String extra, String key, String fallback) {
        if (extra == null || key == null) return fallback;
        try {
            String needle = "\"" + key + "\":\"";
            int i = extra.indexOf(needle);
            if (i < 0) return fallback;
            int p = i + needle.length();
            int end = extra.indexOf('"', p);
            if (end <= p) return fallback;
            return extra.substring(p, end);
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int floorDiv(int value, int div) { return Math.floorDiv(value, div); }

    private static String safeString(String s) { return s == null ? "" : s; }
    private static String urlEncode(String s) { return URLEncoder.encode(safeString(s), StandardCharsets.UTF_8); }
    private static String nullIfBlank(String s) { return s == null || s.isBlank() ? null : s; }

    private static long getLong(JsonObject o, String key) {
        try { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : 0L; }
        catch (Throwable ignored) { return 0L; }
    }

    private static long getRequiredLong(JsonObject o, String key) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            throw new IllegalArgumentException("Missing numeric ClickHouse field: " + key);
        }
        return o.get(key).getAsLong();
    }

    private static String getString(JsonObject o, String key) {
        try { return o != null && o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : ""; }
        catch (Throwable ignored) { return ""; }
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try { return UUID.fromString(s); }
        catch (Throwable ignored) { return null; }
    }

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
        UUID actorUuid = e.actorUuid;
        return (e.type == null ? -1 : e.type.ordinal()) + "|" + e.dim + "|" + e.x + '|' + e.y + '|' + e.z
                + "|" + Objects.toString(actorUuid, "")
                + "|" + Objects.toString(e.actorName, "")
                + "|" + Objects.toString(e.entityUuid, "")
                + "|" + Objects.toString(e.entityType, "")
                + "|" + Objects.toString(e.blockAfter, "")
                + "|" + Objects.toString(e.itemStackNbt, "")
                + "|" + Objects.toString(e.extra, "");
    }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }
}
