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

        public final ModConfigSpec.ConfigValue<String> dbHost;
        public final ModConfigSpec.IntValue dbPort;
        public final ModConfigSpec.ConfigValue<String> dbName;
        public final ModConfigSpec.ConfigValue<String> dbUser;
        public final ModConfigSpec.ConfigValue<String> dbPassword;
        public final ModConfigSpec.IntValue dbPoolSize;
        public final ModConfigSpec.IntValue dbBatchSize;
        public final ModConfigSpec.IntValue dbFlushIntervalMs;
        public final ModConfigSpec.IntValue dbQueueCapacity;

        public final ModConfigSpec.BooleanValue logBlocks;
        public final ModConfigSpec.BooleanValue logEntities;
        public final ModConfigSpec.BooleanValue logContainers;
        public final ModConfigSpec.BooleanValue logItemCraftSmelt;
        public final ModConfigSpec.BooleanValue logChat;
        public final ModConfigSpec.IntValue interactionScanTicks;
        public final ModConfigSpec.BooleanValue storeVerboseBeSnapshotsInDeltaLogs;
        public final ModConfigSpec.BooleanValue storeVerboseBeSnapshotsInInteractLogs;

        public final ModConfigSpec.IntValue lookupDefaultLimit;
        public final ModConfigSpec.IntValue chatPageSize;
        public final ModConfigSpec.IntValue guiPageSize;
        public final ModConfigSpec.ConfigValue<String> inspectToolItemId;

        Values(ModConfigSpec.Builder b) {
            b.push("general");
            enabled = b.comment("Master switch.").define("enabled", true);
            keepDays = b.comment("Delete log files older than N days.")
                    .defineInRange("keepDays", 30, 1, 365);
            b.pop();

            b.push("mysql");
            dbHost = b.comment("MySQL host (XAMPP default: 127.0.0.1)").define("host", "127.0.0.1");
            dbPort = b.comment("MySQL port (XAMPP default: 3306)").defineInRange("port", 3306, 1, 65535);
            dbName = b.comment("Database name. Must exist (create it in phpMyAdmin or via SQL).")
                    .define("database", "minecraft");
            dbUser = b.comment("Database user (XAMPP default: root)").define("user", "root");
            dbPassword = b.comment("Database password (XAMPP default: empty)").define("password", "");
            dbPoolSize = b.comment("Connection pool size.").defineInRange("poolSize", 5, 1, 50);
            dbBatchSize = b.comment("Rows per INSERT batch.").defineInRange("batchSize", 250, 1, 5000);
            dbFlushIntervalMs = b.comment("Force flush interval for the writer thread (ms).")
                    .defineInRange("flushIntervalMs", 250, 50, 5000);
            dbQueueCapacity = b.comment("In-memory queue capacity. If full, new logs will be dropped to protect TPS.")
                    .defineInRange("queueCapacity", 50000, 10000, 500000);
            b.pop();

            b.push("capture");
            logBlocks = b.define("logBlocks", true);
            logEntities = b.define("logEntities", true);
            logContainers = b.define("logContainers", true);
            logItemCraftSmelt = b.define("logItemCraftSmelt", true);
            logChat = b.comment("Log chat messages (vanilla server chat packets).")
                    .define("logChat", true);
            interactionScanTicks = b.comment("How many delayed ticks to watch after block interaction for state/NBT changes. Lower = less TPS impact.")
                    .defineInRange("interactionScanTicks", 3, 1, 10);
            storeVerboseBeSnapshotsInDeltaLogs = b.comment("Store full before/after block-entity NBT inside each CONTAINER_PUT/CONTAINER_TAKE delta log. Disabling removes large duplicate payloads.")
                    .define("storeVerboseBeSnapshotsInDeltaLogs", false);
            storeVerboseBeSnapshotsInInteractLogs = b.comment("Store full before/after block-entity NBT inside BLOCK_INTERACT logs when a separate snapshot entry is also written. Disabling reduces duplicate payloads.")
                    .define("storeVerboseBeSnapshotsInInteractLogs", false);
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
