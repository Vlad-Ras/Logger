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

        public final ModConfigSpec.ConfigValue<String> clickHouseUrl;
        public final ModConfigSpec.ConfigValue<String> clickHouseDatabase;
        public final ModConfigSpec.ConfigValue<String> clickHouseTable;
        public final ModConfigSpec.ConfigValue<String> clickHouseUser;
        public final ModConfigSpec.ConfigValue<String> clickHousePassword;
        public final ModConfigSpec.IntValue clickHouseBatchSize;
        public final ModConfigSpec.IntValue clickHouseFlushIntervalMs;
        public final ModConfigSpec.IntValue clickHouseQueueCapacity;
        public final ModConfigSpec.IntValue clickHouseSelectQueryTimeoutSec;
        public final ModConfigSpec.IntValue clickHouseWorldQueryTimeoutSec;
        public final ModConfigSpec.BooleanValue clickHouseAsyncInsert;
        public final ModConfigSpec.BooleanValue clickHouseWaitForAsyncInsert;
        public final ModConfigSpec.IntValue clickHouseHealthCooldownMs;
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
        public final ModConfigSpec.BooleanValue logPlayerLifecycle;
        public final ModConfigSpec.BooleanValue logItemUse;
        public final ModConfigSpec.BooleanValue logItemConsume;
        public final ModConfigSpec.BooleanValue logItemUsePhases;
        public final ModConfigSpec.BooleanValue logProjectileShots;
        public final ModConfigSpec.BooleanValue logProjectileHits;
        public final ModConfigSpec.BooleanValue logGuiOpen;
        public final ModConfigSpec.BooleanValue logEntityAttacks;
        public final ModConfigSpec.BooleanValue logGenericBlockUse;
        public final ModConfigSpec.IntValue genericActionCooldownMs;
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
            keepDays = b.comment("Delete log rows older than N days. Set to 0 to keep history indefinitely.")
                    .defineInRange("keepDays", 365, 0, 3650);
            b.pop();

            b.push("clickhouse");
            clickHouseUrl = b.comment("ClickHouse HTTP endpoint. The database below is created automatically.")
                    .define("url", "http://127.0.0.1:8123");
            clickHouseDatabase = b.comment("ClickHouse database name for logger tables.")
                    .define("database", "avilix_logger");
            clickHouseTable = b.comment("Previous unified ClickHouse table name. Used only when compatibility reads are enabled.")
                    .define("table", "avilixlogger_actions");
            clickHouseSchemaMode = b.comment("ClickHouse schema mode: split = optimized separate tables by log domain; legacy = old single-table storage.")
                    .define("schemaMode", "split");
            clickHouseTablePrefix = b.comment("Prefix for split ClickHouse tables. The logger creates: <prefix>_blocks, _containers, _entities, _items, _players, _chat, _compat.")
                    .define("tablePrefix", "avilixlogger");
            clickHouseReadLegacyUnifiedTable = b.comment("Compatibility read for the old unified ClickHouse table. Disabled by default; this does not read or migrate MySQL.")
                    .define("readLegacyUnifiedTable", false);
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
                    .defineInRange("feedKeepDays", 0, 0, 3650);
            clickHouseDetailKeepDays = b.comment("Retention for heavy detail/rollback tables. 0 = use general keepDays.")
                    .defineInRange("detailKeepDays", 0, 0, 3650);
            clickHouseChatKeepDays = b.comment("Retention for chat logs in ClickHouse. 0 = use detailKeepDays/general keepDays.")
                    .defineInRange("chatKeepDays", 0, 0, 3650);
            clickHouseCompatKeepDays = b.comment("Retention for compat logs such as planes/trains/aeronautics. 0 = use detailKeepDays/general keepDays.")
                    .defineInRange("compatKeepDays", 0, 0, 3650);
            clickHouseAddSkippingIndexes = b.comment("Create ClickHouse data skipping indexes for actor/target/block/item/entity filters.")
                    .define("addSkippingIndexes", true);
            clickHouseUser = b.comment("ClickHouse user.").define("user", "default");
            clickHousePassword = b.comment("ClickHouse password.").define("password", "");
            clickHouseBatchSize = b.comment("Rows per ClickHouse INSERT batch.")
                    .defineInRange("batchSize", 5000, 100, 100000);
            clickHouseFlushIntervalMs = b.comment("Force flush interval for ClickHouse writer thread (ms).")
                    .defineInRange("flushIntervalMs", 1000, 100, 10000);
            clickHouseQueueCapacity = b.comment("In-memory ClickHouse queue capacity. If full, new logs are dropped to protect TPS.")
                    .defineInRange("queueCapacity", 500000, 10000, 2000000);
            clickHouseSelectQueryTimeoutSec = b.comment("Statement timeout for ClickHouse SELECT queries used by lookup / GUI.")
                    .defineInRange("selectQueryTimeoutSec", 15, 1, 600);
            clickHouseWorldQueryTimeoutSec = b.comment("Timeout for whole-world, all-dimension and 30+ day queries. They run outside the server tick.")
                    .defineInRange("worldQueryTimeoutSec", 600, 5, 3600);
            clickHouseAsyncInsert = b.comment("Enable ClickHouse server-side async inserts with wait_for_async_insert control.")
                    .define("asyncInsert", true);
            clickHouseWaitForAsyncInsert = b.comment("When asyncInsert is enabled, wait until ClickHouse confirms the buffered insert.")
                    .define("waitForAsyncInsert", true);
            clickHouseHealthCooldownMs = b.comment("How long ClickHouse remains marked unhealthy after a failed request (ms).")
                    .defineInRange("healthCooldownMs", 10000, 1000, 300000);
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
            logPlayerLifecycle = b.comment("Log low-noise player lifecycle events: dimension change and respawn.")
                    .define("logPlayerLifecycle", true);
            logItemUse = b.comment("Log right-click item use. Protected by genericActionCooldownMs to prevent spam.")
                    .define("logItemUse", true);
            logItemConsume = b.comment("Log finished item usage such as eating/drinking/consuming.")
                    .define("logItemConsume", true);
            logItemUsePhases = b.comment("Log long item use phases: bow/crossbow charge/release, drinking potions, shields and similar hold-use actions.")
                    .define("logItemUsePhases", true);
            logProjectileShots = b.comment("Log player projectile launches: arrows, crossbow shots, thrown potions, tridents, snowballs and similar projectiles.")
                    .define("logProjectileShots", true);
            logProjectileHits = b.comment("Log projectile impacts/hits when NeoForge exposes ProjectileImpactEvent or damage source data.")
                    .define("logProjectileHits", true);
            logGuiOpen = b.comment("Log non-container GUI/menu opens such as crafting table, anvil, villager trading and modded menus. Container opens stay under CONTAINER_OPEN.")
                    .define("logGuiOpen", true);
            logEntityAttacks = b.comment("Log player attacks on entities. Protected by genericActionCooldownMs to prevent combat spam.")
                    .define("logEntityAttacks", true);
            logGenericBlockUse = b.comment("Log right-click block use even when the block state does not change. Useful for audits, but noisy; protected by genericActionCooldownMs.")
                    .define("logGenericBlockUse", true);
            genericActionCooldownMs = b.comment("Per-player cooldown for generic ITEM_USE/BLOCK_USE/ENTITY_ATTACK logs. Keeps expanded logging from flooding ClickHouse.")
                    .defineInRange("genericActionCooldownMs", 500, 0, 10000);
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
