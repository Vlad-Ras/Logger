package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Migration-safe storage mode.
 *
 * writeMode:
 * - mirror: write every new row to both storages.
 * - primary_only: write only to the preferred/primary storage, but keep the second storage
 *   opened for reads. This is the recommended ClickHouse migration mode when old MySQL
 *   rows must stay visible in GUI/rollback search without continuing to grow MySQL.
 *
 * readMode:
 * - primary_fallback: read preferred DB first, fallback only when it fails/returns empty.
 * - smart_merge: read preferred DB first; query fallback only when the preferred DB fails
 *   or returns fewer rows than requested. Recommended for ClickHouse-primary migration.
 * - merge: query both DBs, deduplicate, sort and return one combined page. This is the
 *   safest but heaviest migration mode.
 */
public final class DualLogStorage implements LogStorage {
    private final LogStorage first;
    private final LogStorage second;
    private final boolean preferFirstReads;
    private final String readMode;
    private final String writeMode;

    public DualLogStorage(LogStorage first, LogStorage second, boolean preferFirstReads) {
        this(first, second, preferFirstReads, "primary_fallback", "mirror");
    }

    public DualLogStorage(LogStorage first, LogStorage second, boolean preferFirstReads, String readMode) {
        this(first, second, preferFirstReads, readMode, "mirror");
    }

    public DualLogStorage(LogStorage first, LogStorage second, boolean preferFirstReads, String readMode, String writeMode) {
        this.first = first;
        this.second = second;
        this.preferFirstReads = preferFirstReads;
        this.readMode = normalizeReadMode(readMode);
        this.writeMode = normalizeWriteMode(writeMode);
    }

    @Override
    public void append(LogEntry entry) {
        if (entry == null) return;
        // Assign one backend-independent id before copying. This keeps cursor pagination
        // comparable across MySQL and ClickHouse for all new dual-mode rows.
        LogIdGenerator.ensure(entry);
        if ("primary_only".equals(writeMode)) {
            LogStorage primary = preferFirstReads ? first : second;
            String primaryName = preferFirstReads ? "first/preferred" : "second/preferred";
            try {
                primary.append(copy(entry));
            } catch (Throwable t) {
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] Dual storage {} append failed in primary_only mode", primaryName, t);
            }
            return;
        }

        try { first.append(copy(entry)); } catch (Throwable t) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Dual storage first append failed", t); }
        try { second.append(copy(entry)); } catch (Throwable t) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Dual storage second append failed", t); }
    }

    @Override
    public List<LogEntry> query(LogQuery q) {
        return read(q, false);
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery q) {
        return read(q, true);
    }

    private List<LogEntry> read(LogQuery q, boolean reverse) {
        if ("merge".equals(readMode)) {
            return mergeRead(q, reverse);
        }
        if ("smart_merge".equals(readMode)) {
            return smartMergeRead(q, reverse);
        }
        return primaryFallbackRead(q, reverse);
    }

    private List<LogEntry> primaryFallbackRead(LogQuery q, boolean reverse) {
        LogStorage primary = preferFirstReads ? first : second;
        LogStorage fallback = preferFirstReads ? second : first;
        try {
            List<LogEntry> got = reverse ? primary.queryReverse(q) : primary.query(q);
            if (got != null && !got.isEmpty()) return got;
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual storage primary read failed, trying fallback", t);
        }
        try {
            List<LogEntry> got = reverse ? fallback.queryReverse(q) : fallback.query(q);
            return got == null ? List.of() : got;
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Dual storage fallback read failed", t);
            return List.of();
        }
    }

    private List<LogEntry> mergeRead(LogQuery q, boolean reverse) {
        int limit = q == null ? 100 : Math.max(1, q.limit);
        Map<String, LogEntry> merged = new LinkedHashMap<>();

        LogStorage primary = preferFirstReads ? first : second;
        LogStorage fallback = preferFirstReads ? second : first;

        readInto(merged, primary, q, reverse, "primary");
        readInto(merged, fallback, q, reverse, "fallback");

        ArrayList<LogEntry> out = new ArrayList<>(merged.values());
        Comparator<LogEntry> cmp = Comparator
                .comparingLong((LogEntry e) -> e == null ? 0L : e.id);
        if (reverse) cmp = cmp.reversed();
        out.sort(cmp);

        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    /**
     * Optimized migration read mode. For normal GUI/history lookups we only need MySQL
     * when ClickHouse cannot fill the requested page. This keeps old MySQL rows visible
     * without hitting MySQL on every fresh ClickHouse page.
     */
    private List<LogEntry> smartMergeRead(LogQuery q, boolean reverse) {
        int limit = q == null ? 100 : Math.max(1, q.limit);
        LogStorage primary = preferFirstReads ? first : second;
        LogStorage fallback = preferFirstReads ? second : first;

        List<LogEntry> primaryRows = List.of();
        boolean primaryFailed = false;
        try {
            List<LogEntry> got = reverse ? primary.queryReverse(q) : primary.query(q);
            primaryRows = got == null ? List.of() : got;
        } catch (Throwable t) {
            primaryFailed = true;
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual storage primary read failed during smart_merge; trying fallback", t);
        }

        // Reverse reads are used by GUI/history/rollback. If the ClickHouse page is full,
        // older MySQL archive rows cannot improve that latest page in the intended
        // ClickHouse-primary migration layout. Skipping MySQL here removes most load.
        if (!primaryFailed && reverse && primaryRows.size() >= limit) {
            return sortedLimited(primaryRows, limit, true);
        }

        Map<String, LogEntry> merged = new LinkedHashMap<>();
        putInto(merged, primaryRows);
        readInto(merged, fallback, q, reverse, "fallback");
        return sortedLimited(new ArrayList<>(merged.values()), limit, reverse);
    }

    private void readInto(Map<String, LogEntry> merged, LogStorage storage, LogQuery q, boolean reverse, String name) {
        if (storage == null) return;
        try {
            List<LogEntry> got = reverse ? storage.queryReverse(q) : storage.query(q);
            putInto(merged, got);
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual storage {} read failed during merge read", name, t);
        }
    }

    private void putInto(Map<String, LogEntry> merged, List<LogEntry> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (LogEntry e : rows) {
            if (e == null) continue;
            // Existing pre-migration MySQL rows may still have old AUTO_INCREMENT ids.
            // Keep them as-is; new dual/failover rows use LogIdGenerator-compatible ids.
            merged.putIfAbsent(dedupeKey(e), e);
        }
    }

    private static List<LogEntry> sortedLimited(List<LogEntry> rows, int limit, boolean reverse) {
        ArrayList<LogEntry> out = new ArrayList<>();
        if (rows != null) {
            for (LogEntry e : rows) {
                if (e != null) out.add(e);
            }
        }
        Comparator<LogEntry> cmp = Comparator.comparingLong(e -> e.id);
        if (reverse) cmp = cmp.reversed();
        out.sort(cmp);
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(0, limit));
        }
        return out;
    }

    private static String normalizeReadMode(String mode) {
        String m = mode == null ? "primary_fallback" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "merge", "merged", "both", "both_databases" -> "merge";
            case "smart_merge", "smart", "lazy_merge", "lazy", "optimized_merge", "optimised_merge" -> "smart_merge";
            default -> "primary_fallback";
        };
    }

    private static String normalizeWriteMode(String mode) {
        String m = mode == null ? "mirror" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "primary_only", "preferred_only", "primary", "clickhouse_only", "ch_only" -> "primary_only";
            default -> "mirror";
        };
    }

    private static String dedupeKey(LogEntry e) {
        return e.ts + "|" + e.dim + "|" + e.x + '|' + e.y + '|' + e.z + '|'
                + (e.type == null ? -1 : e.type.ordinal()) + '|'
                + String.valueOf(e.actorUuid) + '|'
                + String.valueOf(e.actorName) + '|'
                + String.valueOf(e.blockBefore) + '|'
                + String.valueOf(e.blockAfter) + '|'
                + String.valueOf(e.entityUuid) + '|'
                + String.valueOf(e.itemStackNbt) + '|'
                + String.valueOf(e.extra);
    }

    private static LogEntry copy(LogEntry e) {
        if (e == null) return null;
        LogEntry c = new LogEntry();
        c.id = e.id;
        c.ts = e.ts;
        c.dim = e.dim;
        c.type = e.type;
        c.actorUuid = e.actorUuid;
        c.actorName = e.actorName;
        c.source = e.source;
        c.x = e.x;
        c.y = e.y;
        c.z = e.z;
        c.blockBefore = e.blockBefore;
        c.beBefore = e.beBefore;
        c.blockAfter = e.blockAfter;
        c.beAfter = e.beAfter;
        c.entityType = e.entityType;
        c.entityUuid = e.entityUuid;
        c.entityNbt = e.entityNbt;
        c.itemStackNbt = e.itemStackNbt;
        c.count = e.count;
        c.playerInvBefore = e.playerInvBefore;
        c.playerInvAfter = e.playerInvAfter;
        c.containerSlotsBefore = e.containerSlotsBefore;
        c.containerSlotsAfter = e.containerSlotsAfter;
        c.extra = e.extra;
        return c;
    }

    @Override
    public void shutdown() {
        try { first.shutdown(); } catch (Throwable t) { AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual storage first shutdown failed", t); }
        try { second.shutdown(); } catch (Throwable t) { AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Dual storage second shutdown failed", t); }
    }
}
