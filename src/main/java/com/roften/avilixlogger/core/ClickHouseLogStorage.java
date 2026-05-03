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
 * compressed JSON blob for every filter. The old unified table can still be read for migration.
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
                ActionType.ENTITY_OWNER_SET);
        map(TableKind.ITEMS,
                ActionType.ITEM_DROP,
                ActionType.ITEM_PICKUP,
                ActionType.ITEM_CRAFT,
                ActionType.ITEM_SMELT);
        map(TableKind.PLAYERS,
                ActionType.PLAYER_DEATH,
                ActionType.PLAYER_JOIN,
                ActionType.PLAYER_LEAVE);
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
            cooldown = Math.max(1000L, LoggerConfig.VALUES.dualFallbackCooldownMs.get());
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
                if (addSkippingIndexes) ensureSkippingIndexes();
                AvilixLoggerMod.LOGGER.info("[AvilixLogger] ClickHouse split+feed schema ready. DB={}, prefix={}, feed={}, detailMode={}", database, tablePrefix, useFeedTable, detailMode);
            } else {
                ensureLegacyTable();
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
        long everyMs = 6L * 60L * 60_000L;
        if ((now - lastCleanupAt) < everyMs) return;
        lastCleanupAt = now;

        try {
            if (splitSchema) {
                if (useFeedTable) cleanupTable(qualifiedFeedTable(), retentionDaysFor(TableKind.FEED), now);
                for (TableKind kind : splitKinds()) {
                    cleanupTable(qualifiedSplitTable(kind), retentionDaysFor(kind), now);
                }
            } else {
                cleanupTable(qualifiedLegacyTable(), LoggerConfig.VALUES.keepDays.get(), now);
            }
        } catch (Throwable e) {
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] ClickHouse cleanup mutation failed", e);
        }
    }


    private void cleanupTable(String table, int keepDays, long now) throws IOException {
        if (keepDays <= 0) return;
        long cutoff = now - (long) keepDays * 24L * 60L * 60_000L;
        execute("ALTER TABLE " + table + " DELETE WHERE ts_ms < " + cutoff, timeoutSec());
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
        if (useFeedTable && !reverse) return selectFeed(q, false);

        List<TableKind> kinds = tablesForQuery(q);
        ArrayList<LogEntry> out = new ArrayList<>();
        try {
            if (!kinds.isEmpty()) {
                String sql = buildSplitSelectSql(q, reverse, kinds);
                out.addAll(parseSplitJsonEachRow(execute(sql, timeoutSec())));
            }
            if (readLegacyUnifiedTable) {
                // Existing rows written by older ClickHouse builds remain visible during migration.
                // Errors are ignored because fresh split installations do not have the legacy table.
                try {
                    out.addAll(selectLegacy(q, reverse));
                } catch (Throwable ignored) {}
            }
            if (reverse && useFeedTable && (out.isEmpty() || Math.max(1, q.limit) <= 128)) {
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
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse split query failed", e);
            return List.of();
        }
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
            List<LogEntry> rows = parseFeedJsonEachRow(execute(sql.toString(), timeoutSec()));
            markHealthy();
            return rows;
        } catch (Throwable e) {
            markUnhealthy(e);
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] ClickHouse feed query failed", e);
            return List.of();
        }
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
                .append("id, ts_ms, dim, x, y, z, action, actor_name, actor_uuid, source, extra, ")
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
        appendWhere(sql, q, reverse);
        return sql.toString();
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
        sql.append(" WHERE ts_ms >= ").append(Math.max(0L, q.sinceTs))
                .append(" AND ts_ms <= ").append(Math.max(0L, q.untilTs));

        boolean allDims = q.dim == null || "*".equals(q.dim);
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
            sql.append(" AND actor_name_lc = ").append(sqlString(q.actorName.toLowerCase(Locale.ROOT)));
        }

        appendPositionWhere(sql, q);

        if (reverse) {
            if (q.beforeId > 0) sql.append(" AND id < ").append(q.beforeId);
        } else {
            if (q.afterId > 0) sql.append(" AND id > ").append(q.afterId);
        }
    }

    private void appendWhere(StringBuilder sql, LogQuery q, boolean reverse) {
        sql.append(" WHERE ts_ms >= ").append(Math.max(0L, q.sinceTs))
                .append(" AND ts_ms <= ").append(Math.max(0L, q.untilTs));

        boolean allDims = q.dim == null || "*".equals(q.dim);
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
            sql.append(" AND actor_name_lc = ").append(sqlString(q.actorName.toLowerCase(Locale.ROOT)));
        }

        appendPositionWhere(sql, q);

        if (reverse) {
            if (q.beforeId > 0) sql.append(" AND id < ").append(q.beforeId);
        } else {
            if (q.afterId > 0) sql.append(" AND id > ").append(q.afterId);
        }
    }


    private void appendPositionWhere(StringBuilder sql, LogQuery q) {
        if (q.exactPos != null) {
            int x = q.exactPos.getX();
            int y = q.exactPos.getY();
            int z = q.exactPos.getZ();
            sql.append(" AND region_x = ").append(floorDiv(x, 512))
                    .append(" AND region_z = ").append(floorDiv(z, 512))
                    .append(" AND chunk_x = ").append(floorDiv(x, 16))
                    .append(" AND chunk_z = ").append(floorDiv(z, 16))
                    .append(" AND x = ").append(x)
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
            sql.append(" AND region_x BETWEEN ").append(floorDiv(minX, 512)).append(" AND ").append(floorDiv(maxX, 512))
                    .append(" AND region_z BETWEEN ").append(floorDiv(minZ, 512)).append(" AND ").append(floorDiv(maxZ, 512))
                    .append(" AND chunk_x BETWEEN ").append(floorDiv(minX, 16)).append(" AND ").append(floorDiv(maxX, 16))
                    .append(" AND chunk_z BETWEEN ").append(floorDiv(minZ, 16)).append(" AND ").append(floorDiv(maxZ, 16))
                    .append(" AND x BETWEEN ").append(minX).append(" AND ").append(maxX)
                    .append(" AND y BETWEEN ").append(minY).append(" AND ").append(maxY)
                    .append(" AND z BETWEEN ").append(minZ).append(" AND ").append(maxZ);
        }
    }

    private List<LogEntry> selectLegacy(LogQuery q, boolean reverse) {
        StringBuilder sql = new StringBuilder();
        boolean allDims = q.dim == null || "*".equals(q.dim);
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
            sql.append(" AND actor_name = ").append(sqlString(q.actorName));
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
            String response = execute(sql.toString(), timeoutSec());
            if (response == null || response.isBlank()) {
                markHealthy();
                return out;
            }
            String[] lines = response.split("\\R");
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                long id;
                try { id = Long.parseLong(line.substring(0, tab).trim()); }
                catch (NumberFormatException ignored) { continue; }
                String data = line.substring(tab + 1).trim();
                if (data.isBlank()) continue;
                byte[] gz = Base64.getDecoder().decode(data);
                LogEntry e = GzipJson.fromGzippedJsonBytes(gz, LogEntry.class);
                e.id = id;
                out.add(e);
            }
            markHealthy();
        } catch (Throwable e) {
            if (!splitSchema || readLegacyUnifiedTable) {
                // In split mode this is expected for fresh installs without the old table.
                AvilixLoggerMod.LOGGER.debug("[AvilixLogger] ClickHouse legacy query skipped/failed", e);
            }
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
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                LogEntry e = new LogEntry();
                e.id = getLong(o, "id");
                e.ts = getLong(o, "ts_ms");
                e.dim = nullIfBlank(getString(o, "dim"));
                int action = (int) getLong(o, "action");
                ActionType[] values = ActionType.values();
                e.type = action >= 0 && action < values.length ? values[action] : ActionType.BLOCK_INTERACT;
                e.actorName = nullIfBlank(getString(o, "actor_name"));
                e.actorUuid = parseUuid(getString(o, "actor_uuid"));
                e.source = nullIfBlank(getString(o, "source"));
                e.x = (int) getLong(o, "x");
                e.y = (int) getLong(o, "y");
                e.z = (int) getLong(o, "z");
                String targetKind = getString(o, "target_kind");
                String targetId = getString(o, "target_id");
                String shortText = getString(o, "short_text");
                e.count = (int) getLong(o, "item_count");
                hydrateFeedTarget(e, targetKind, targetId, shortText);
                e.extra = nullIfBlank(getString(o, "extra"));
                if (e.type == ActionType.CHAT_MESSAGE && e.extra == null) e.extra = shortText;
                out.add(e);
            } catch (Throwable ignored) {}
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
        for (String line : lines) {
            if (line == null || line.isBlank()) continue;
            try {
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                out.add(fromJson(o));
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private LogEntry fromJson(JsonObject o) {
        LogEntry e = new LogEntry();
        e.id = getLong(o, "id");
        e.ts = getLong(o, "ts_ms");
        e.dim = nullIfBlank(getString(o, "dim"));
        int action = (int) getLong(o, "action");
        ActionType[] values = ActionType.values();
        e.type = action >= 0 && action < values.length ? values[action] : null;
        e.actorName = nullIfBlank(getString(o, "actor_name"));
        e.actorUuid = parseUuid(getString(o, "actor_uuid"));
        e.source = nullIfBlank(getString(o, "source"));
        e.x = (int) getLong(o, "x");
        e.y = (int) getLong(o, "y");
        e.z = (int) getLong(o, "z");
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
        catch (Throwable ignored) { return 5; }
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
            case BLOCK_PLACE, BLOCK_INTERACT, BLOCK_ENTITY_NBT_CHANGE, CONTAINER_OPEN -> targetBlock(e.blockAfter, e.extra);
            case CONTAINER_PUT, CONTAINER_TAKE, ITEM_PICKUP, ITEM_DROP, ITEM_CRAFT, ITEM_SMELT,
                 PLANE_PICKUP, TRAIN_SCHEDULE_TAKE, TRAIN_SCHEDULE_PUT -> targetItem(e.itemStackNbt, e.count, e.extra);
            case ENTITY_DEATH, ENTITY_SPAWN, ENTITY_MOUNT, ENTITY_DISMOUNT, ENTITY_CONTAINER_OPEN,
                 ENTITY_INTERACT, ENTITY_OWNER_SET, PLANE_PLACE, PLANE_REMOVE, PLANE_MOUNT ->
                    new TargetInfo("entity", safeString(e.entityType), safeString(e.entityType));
            case CHAT_MESSAGE -> new TargetInfo("chat", "", safeString(e.extra));
            case TRAIN_ASSEMBLE, TRAIN_DISASSEMBLE, TRAIN_CONTROL_START, TRAIN_CONTROL_STOP ->
                    new TargetInfo("train", extractExtraName(e.extra, "trainName", "поезд"), safeString(e.extra));
            case PLAYER_DEATH, PLAYER_JOIN, PLAYER_LEAVE ->
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
