package com.roften.avilixlogger;

import net.neoforged.neoforge.common.ModConfigSpec;
import org.apache.commons.lang3.tuple.Pair;

/**
 * Common config for Avilix Logger.
 */
public final class LoggerConfig {
    public static final ModConfigSpec SPEC;
    public static final Values VALUES;

    static {
        final Pair<Values, ModConfigSpec> specPair = new ModConfigSpec.Builder().configure(Values::new);
        VALUES = specPair.getLeft();
        SPEC = specPair.getRight();
    }

    public static final class Values {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.IntValue keepDays;
        public final ModConfigSpec.ConfigValue<String> storageBackend;

        public final ModConfigSpec.ConfigValue<String> dbHost;
        public final ModConfigSpec.IntValue dbPort;
        public final ModConfigSpec.ConfigValue<String> dbName;
        public final ModConfigSpec.ConfigValue<String> dbUser;
        public final ModConfigSpec.ConfigValue<String> dbPassword;
        public final ModConfigSpec.IntValue dbPoolSize;
        public final ModConfigSpec.IntValue dbBatchSize;
        public final ModConfigSpec.IntValue dbFlushIntervalMs;
        public final ModConfigSpec.IntValue dbQueueCapacity;
        public final ModConfigSpec.IntValue dbCleanupBatchSize;
        public final ModConfigSpec.IntValue dbCleanupMaxBatchesPerRun;
        public final ModConfigSpec.IntValue dbCleanupQueryTimeoutSec;
        public final ModConfigSpec.IntValue dbSelectQueryTimeoutSec;

        public final ModConfigSpec.ConfigValue<String> clickHouseUrl;
        public final ModConfigSpec.ConfigValue<String> clickHouseDatabase;
        public final ModConfigSpec.ConfigValue<String> clickHouseTable;
        public final ModConfigSpec.ConfigValue<String> clickHouseUser;
        public final ModConfigSpec.ConfigValue<String> clickHousePassword;
        public final ModConfigSpec.IntValue clickHousePoolSize;
        public final ModConfigSpec.IntValue clickHouseBatchSize;
        public final ModConfigSpec.IntValue clickHouseFlushIntervalMs;
        public final ModConfigSpec.IntValue clickHouseQueueCapacity;
        public final ModConfigSpec.IntValue clickHouseSelectQueryTimeoutSec;
        public final ModConfigSpec.BooleanValue clickHouseAsyncInsert;
        public final ModConfigSpec.BooleanValue clickHouseWaitForAsyncInsert;
        public final ModConfigSpec.BooleanValue dualPreferClickHouseReads;
        public final ModConfigSpec.ConfigValue<String> dualWriteMode;
        public final ModConfigSpec.ConfigValue<String> dualReadMode;
        public final ModConfigSpec.IntValue dualFallbackCooldownMs;
        public final ModConfigSpec.ConfigValue<String> clickHouseSchemaMode;
        public final ModConfigSpec.ConfigValue<String> clickHouseTablePrefix;
        public final ModConfigSpec.BooleanValue clickHouseReadLegacyUnifiedTable;
        public final ModConfigSpec.BooleanValue clickHouseUseZstdCodec;
        public final ModConfigSpec.BooleanValue clickHouseUseFeedTable;
        public final ModConfigSpec.BooleanValue clickHouseStoreRollbackDetails;
        public final ModConfigSpec.BooleanValue clickHouseStoreNonRollbackDetails;
        public final ModConfigSpec.ConfigValue<String> clickHouseDetailMode;
        public final ModConfigSpec.IntValue clickHouseFeedKeepDays;
        public final ModConfigSpec.IntValue clickHouseDetailKeepDays;
        public final ModConfigSpec.IntValue clickHouseChatKeepDays;
        public final ModConfigSpec.IntValue clickHouseCompatKeepDays;
        public final ModConfigSpec.BooleanValue clickHouseAddSkippingIndexes;

        public final ModConfigSpec.IntValue asyncWorkerThreads;
        public final ModConfigSpec.IntValue asyncQueueCapacity;

        public final ModConfigSpec.BooleanValue logBlocks;
        public final ModConfigSpec.BooleanValue logEntities;
        public final ModConfigSpec.BooleanValue logContainers;
        public final ModConfigSpec.BooleanValue logItemCraftSmelt;
        public final ModConfigSpec.BooleanValue logChat;
        public final ModConfigSpec.IntValue interactionScanTicks;
        public final ModConfigSpec.BooleanValue storeVerboseBeSnapshotsInDeltaLogs;
        public final ModConfigSpec.BooleanValue storeVerboseBeSnapshotsInInteractLogs;
        public final ModConfigSpec.BooleanValue storeVerboseEntitySnapshotsInInteractLogs;
        public final ModConfigSpec.BooleanValue storeVerboseEntitySnapshotsInMountLogs;

        public final ModConfigSpec.IntValue lookupDefaultLimit;
        public final ModConfigSpec.IntValue chatPageSize;
        public final ModConfigSpec.IntValue guiPageSize;
        public final ModConfigSpec.ConfigValue<String> inspectToolItemId;

        Values(ModConfigSpec.Builder b) {
            b.push("general");
            enabled = b.comment("Master switch.").define("enabled", true);
            keepDays = b.comment("Delete log rows older than N days. Set to 0 to disable automatic cleanup.")
                    .defineInRange("keepDays", 30, 0, 365);
            storageBackend = b.comment("Storage backend: mysql, clickhouse, dual. Recommended migration mode: dual + dualWriteMode=primary_only + dualReadMode=smart_merge, so new logs go only to ClickHouse while old MySQL logs remain readable with minimal MySQL load.")
                    .define("storageBackend", "dual");
            b.pop();

            b.push("mysql");
            dbHost = b.comment("MySQL host (XAMPP default: 127.0.0.1)").define("host", "127.0.0.1");
            dbPort = b.comment("MySQL port (XAMPP default: 3306)").defineInRange("port", 3306, 1, 65535);
            dbName = b.comment("Database name. Must exist (create it in phpMyAdmin or via SQL).")
                    .define("database", "minecraft");
            dbUser = b.comment("Database user (XAMPP default: root)").define("user", "root");
            dbPassword = b.comment("Database password (XAMPP default: empty)").define("password", "");
            dbPoolSize = b.comment("Connection pool size.").defineInRange("poolSize", 5, 1, 50);
            dbBatchSize = b.comment("Rows per INSERT batch.").defineInRange("batchSize", 1000, 1, 5000);
            dbFlushIntervalMs = b.comment("Force flush interval for the writer thread (ms).")
                    .defineInRange("flushIntervalMs", 500, 50, 5000);
            dbQueueCapacity = b.comment("In-memory queue capacity. If full, new logs will be dropped to protect TPS.")
                    .defineInRange("queueCapacity", 200000, 10000, 1000000);
            dbCleanupBatchSize = b.comment("How many old rows to delete per cleanup statement. Smaller values reduce lock pressure.")
                    .defineInRange("cleanupBatchSize", 2000, 100, 50000);
            dbCleanupMaxBatchesPerRun = b.comment("How many cleanup DELETE batches may run in one periodic cleanup pass.")
                    .defineInRange("cleanupMaxBatchesPerRun", 4, 1, 100);
            dbCleanupQueryTimeoutSec = b.comment("Statement timeout for cleanup DELETE queries. Keeps shutdown responsive when DB is under load.")
                    .defineInRange("cleanupQueryTimeoutSec", 3, 1, 60);
            dbSelectQueryTimeoutSec = b.comment("Statement timeout for SELECT queries used by lookup / GUI. Prevents the GUI from hanging forever on heavy scans.")
                    .defineInRange("selectQueryTimeoutSec", 5, 1, 60);
            b.pop();

            b.push("clickhouse");
            clickHouseUrl = b.comment("ClickHouse JDBC URL. Keep /default here if the logger should auto-create the database.")
                    .define("url", "jdbc:clickhouse:http://127.0.0.1:8123/default");
            clickHouseDatabase = b.comment("ClickHouse database name for logger tables.")
                    .define("database", "avilix_logger");
            clickHouseTable = b.comment("Legacy ClickHouse unified table name. In split schema this is used only for optional migration reads.")
                    .define("table", "avilixlogger_actions");
            clickHouseSchemaMode = b.comment("ClickHouse schema mode: split = optimized separate tables by log domain; legacy = old single-table storage.")
                    .define("schemaMode", "split");
            clickHouseTablePrefix = b.comment("Prefix for split ClickHouse tables. The logger creates: <prefix>_blocks, _containers, _entities, _items, _players, _chat, _compat.")
                    .define("tablePrefix", "avilixlogger");
            clickHouseReadLegacyUnifiedTable = b.comment("When schemaMode=split, also read the old unified ClickHouse table for migration/backward compatibility.")
                    .define("readLegacyUnifiedTable", true);
            clickHouseUseZstdCodec = b.comment("Use ZSTD codecs on large ClickHouse String columns. Reduces disk usage for SNBT/JSON-heavy logs.")
                    .define("useZstdCodec", true);
            clickHouseUseFeedTable = b.comment("Use a lightweight <prefix>_feed table for GUI/list queries. Details and rollback still read from detail tables.")
                    .define("useFeedTable", true);
            clickHouseDetailMode = b.comment("Detail storage mode: minimal, balanced, full, debug. minimal keeps only rollback-critical details; balanced is recommended.")
                    .define("detailMode", "balanced");
            clickHouseStoreRollbackDetails = b.comment("Store full rollback-critical details in ClickHouse detail tables.")
                    .define("storeRollbackDetails", true);
            clickHouseStoreNonRollbackDetails = b.comment("Store full details for non-rollback events too. Disable to reduce database weight.")
                    .define("storeNonRollbackDetails", false);
            clickHouseFeedKeepDays = b.comment("Retention for the lightweight feed table. 0 = use general keepDays.")
                    .defineInRange("feedKeepDays", 90, 0, 3650);
            clickHouseDetailKeepDays = b.comment("Retention for heavy detail/rollback tables. 0 = use general keepDays.")
                    .defineInRange("detailKeepDays", 30, 0, 3650);
            clickHouseChatKeepDays = b.comment("Retention for chat logs in ClickHouse. 0 = use detailKeepDays/general keepDays.")
                    .defineInRange("chatKeepDays", 14, 0, 3650);
            clickHouseCompatKeepDays = b.comment("Retention for compat logs such as planes/trains/aeronautics. 0 = use detailKeepDays/general keepDays.")
                    .defineInRange("compatKeepDays", 60, 0, 3650);
            clickHouseAddSkippingIndexes = b.comment("Create ClickHouse data skipping indexes for actor/target/block/item/entity filters.")
                    .define("addSkippingIndexes", true);
            clickHouseUser = b.comment("ClickHouse user.").define("user", "default");
            clickHousePassword = b.comment("ClickHouse password.").define("password", "");
            clickHousePoolSize = b.comment("ClickHouse connection pool size. Usually 1-3 is enough for one writer plus GUI queries.")
                    .defineInRange("poolSize", 3, 1, 20);
            clickHouseBatchSize = b.comment("Rows per ClickHouse INSERT batch. ClickHouse likes larger batches than MySQL.")
                    .defineInRange("batchSize", 5000, 100, 100000);
            clickHouseFlushIntervalMs = b.comment("Force flush interval for ClickHouse writer thread (ms).")
                    .defineInRange("flushIntervalMs", 1000, 100, 10000);
            clickHouseQueueCapacity = b.comment("In-memory ClickHouse queue capacity. If full, new logs are dropped to protect TPS.")
                    .defineInRange("queueCapacity", 500000, 10000, 2000000);
            clickHouseSelectQueryTimeoutSec = b.comment("Statement timeout for ClickHouse SELECT queries used by lookup / GUI.")
                    .defineInRange("selectQueryTimeoutSec", 5, 1, 120);
            clickHouseAsyncInsert = b.comment("Enable ClickHouse server-side async inserts with wait_for_async_insert control.")
                    .define("asyncInsert", true);
            clickHouseWaitForAsyncInsert = b.comment("When asyncInsert is enabled, wait until ClickHouse confirms the buffered insert.")
                    .define("waitForAsyncInsert", true);
            dualPreferClickHouseReads = b.comment("In storageBackend=dual, prefer ClickHouse for GUI/rollback reads. If false, MySQL is read first.")
                    .define("dualPreferClickHouseReads", true);
            dualWriteMode = b.comment("Dual write mode: primary_only/clickhouse_only = write new logs only to preferred storage (ClickHouse by default) and keep MySQL read-only; mirror = write every log to ClickHouse and MySQL; failover = write to ClickHouse while healthy, otherwise write to MySQL only.")
                    .define("dualWriteMode", "primary_only");
            dualReadMode = b.comment("Dual read mode: smart_merge = read ClickHouse first and query MySQL only when ClickHouse cannot fill the page; merge = always query ClickHouse and MySQL, then deduplicate/sort results; primary_fallback = use MySQL only if ClickHouse fails/returns empty.")
                    .define("dualReadMode", "smart_merge");
            dualFallbackCooldownMs = b.comment("After a ClickHouse/MySQL writer or query failure, keep that backend marked unhealthy for this many milliseconds before using it for new failover writes again.")
                    .defineInRange("dualFallbackCooldownMs", 10000, 1000, 300000);
            b.pop();

            b.push("async");
            asyncWorkerThreads = b.comment("Worker threads for expensive log post-processing: container diffs, BE diffs and GUI-safe aggregation. Does not disable any logging.")
                    .defineInRange("workerThreads", 2, 1, 16);
            asyncQueueCapacity = b.comment("Queue capacity for expensive CPU post-processing tasks. If this is full, increase workerThreads or investigate spammy mods.")
                    .defineInRange("queueCapacity", 100000, 10000, 1000000);
            b.pop();

            b.push("capture");
            logBlocks = b.define("logBlocks", true);
            logEntities = b.define("logEntities", true);
            logContainers = b.define("logContainers", true);
            logItemCraftSmelt = b.define("logItemCraftSmelt", true);
            logChat = b.comment("Log chat messages (vanilla server chat packets).")
                    .define("logChat", true);
            interactionScanTicks = b.comment("How many delayed ticks to watch after block interaction for state/NBT changes. Lower = less TPS impact.")
                    .defineInRange("interactionScanTicks", 1, 1, 10);
            storeVerboseBeSnapshotsInDeltaLogs = b.comment("Store full before/after block-entity NBT inside each CONTAINER_PUT/CONTAINER_TAKE delta log. Disabling removes large duplicate payloads.")
                    .define("storeVerboseBeSnapshotsInDeltaLogs", true);
            storeVerboseBeSnapshotsInInteractLogs = b.comment("Store full before/after block-entity NBT inside BLOCK_INTERACT logs when a separate snapshot entry is also written. Disabling reduces duplicate payloads.")
                    .define("storeVerboseBeSnapshotsInInteractLogs", true);
            storeVerboseEntitySnapshotsInInteractLogs = b.comment("Store full entity NBT on every entity right-click interaction. Very expensive with NPCs/keepers; enabled by default; disable only if you intentionally want smaller rows.")
                    .define("storeVerboseEntitySnapshotsInInteractLogs", true);
            storeVerboseEntitySnapshotsInMountLogs = b.comment("Store full entity NBT on every mount/dismount/container-open interaction. Very expensive with vehicles; enabled by default; disable only if you intentionally want smaller rows.")
                    .define("storeVerboseEntitySnapshotsInMountLogs", true);
            b.pop();

            b.push("commands");
            lookupDefaultLimit = b.comment("Default number of entries returned by /log lookup.")
                    .defineInRange("lookupDefaultLimit", 15, 1, 200);
            chatPageSize = b.comment("How many log lines to show per page (chat pagination and GUI fetch size).")
                    .defineInRange("chatPageSize", 20, 3, 200);
            guiPageSize = b.comment("How many rows the optional GUI fetches per page. Higher values show more history without paging.")
                    .defineInRange("guiPageSize", 60, 10, 400);
            inspectToolItemId = b.comment("Item id for inspect tool (CoreProtect-like). Example: minecraft:stick")
                    .define("inspectToolItemId", "minecraft:stick");
            b.pop();
        }
    }

    public static String inspectToolItemId() {
        try {
            return VALUES.inspectToolItemId.get();
        } catch (Throwable t) {
            return "minecraft:stick";
        }
    }

    private LoggerConfig() {}
}
