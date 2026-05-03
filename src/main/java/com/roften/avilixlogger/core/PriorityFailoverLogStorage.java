package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Priority storage mode: write to the primary backend while it is healthy; when it is not,
 * write to the fallback backend. Useful for ClickHouse-first deployments where MySQL is
 * kept only as an emergency sink.
 */
public final class PriorityFailoverLogStorage implements LogStorage {
    private final LogStorage primary;
    private final LogStorage fallback;
    private final String primaryName;
    private final String fallbackName;
    private final String readMode;

    public PriorityFailoverLogStorage(LogStorage primary, LogStorage fallback, String primaryName, String fallbackName, String readMode) {
        this.primary = primary;
        this.fallback = fallback;
        this.primaryName = primaryName == null ? "primary" : primaryName;
        this.fallbackName = fallbackName == null ? "fallback" : fallbackName;
        this.readMode = normalizeReadMode(readMode);
    }

    @Override
    public void append(LogEntry entry) {
        if (entry == null) return;
        LogIdGenerator.ensure(entry);
        LogStorage target = isAvailable(primary) ? primary : fallback;
        LogStorage other = target == primary ? fallback : primary;
        String targetName = target == primary ? primaryName : fallbackName;
        String otherName = other == primary ? primaryName : fallbackName;

        try {
            target.append(copy(entry));
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} append failed in failover mode; trying {}", targetName, otherName, t);
            try {
                other.append(copy(entry));
            } catch (Throwable t2) {
                AvilixLoggerMod.LOGGER.error("[AvilixLogger] Both storages failed to accept append in failover mode", t2);
            }
        }
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
        return primaryThenFallbackRead(q, reverse);
    }

    private List<LogEntry> primaryThenFallbackRead(LogQuery q, boolean reverse) {
        if (isAvailable(primary)) {
            try {
                List<LogEntry> got = reverse ? primary.queryReverse(q) : primary.query(q);
                if (got != null && !got.isEmpty()) return got;
            } catch (Throwable t) {
                AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} read failed in failover mode; trying {}", primaryName, fallbackName, t);
            }
        }

        try {
            List<LogEntry> got = reverse ? fallback.queryReverse(q) : fallback.query(q);
            return got == null ? List.of() : got;
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] {} read failed in failover mode", fallbackName, t);
            return List.of();
        }
    }

    private List<LogEntry> mergeRead(LogQuery q, boolean reverse) {
        int limit = q == null ? 100 : Math.max(1, q.limit);
        Map<String, LogEntry> merged = new LinkedHashMap<>();

        readInto(merged, primary, q, reverse, primaryName);
        readInto(merged, fallback, q, reverse, fallbackName);

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

    private List<LogEntry> smartMergeRead(LogQuery q, boolean reverse) {
        int limit = q == null ? 100 : Math.max(1, q.limit);
        List<LogEntry> primaryRows = List.of();
        boolean primaryFailed = false;

        if (isAvailable(primary)) {
            try {
                List<LogEntry> got = reverse ? primary.queryReverse(q) : primary.query(q);
                primaryRows = got == null ? List.of() : got;
            } catch (Throwable t) {
                primaryFailed = true;
                AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} read failed during smart_merge; trying {}", primaryName, fallbackName, t);
            }
        } else {
            primaryFailed = true;
        }

        if (!primaryFailed && reverse && primaryRows.size() >= limit) {
            return sortedLimited(primaryRows, limit, true);
        }

        Map<String, LogEntry> merged = new LinkedHashMap<>();
        putInto(merged, primaryRows);
        readInto(merged, fallback, q, reverse, fallbackName);
        return sortedLimited(new ArrayList<>(merged.values()), limit, reverse);
    }

    private void readInto(Map<String, LogEntry> merged, LogStorage storage, LogQuery q, boolean reverse, String name) {
        if (storage == null || !isAvailable(storage)) return;
        try {
            List<LogEntry> got = reverse ? storage.queryReverse(q) : storage.query(q);
            putInto(merged, got);
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} read failed during merge read", name, t);
        }
    }

    private void putInto(Map<String, LogEntry> merged, List<LogEntry> rows) {
        if (rows == null || rows.isEmpty()) return;
        for (LogEntry e : rows) {
            if (e == null) continue;
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

    private static boolean isAvailable(LogStorage storage) {
        if (storage instanceof HealthAwareLogStorage healthAware) {
            return healthAware.isLikelyAvailable();
        }
        return storage != null;
    }

    private static String normalizeReadMode(String mode) {
        String m = mode == null ? "primary_fallback" : mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "merge", "merged", "both", "both_databases" -> "merge";
            case "smart_merge", "smart", "lazy_merge", "lazy", "optimized_merge", "optimised_merge" -> "smart_merge";
            default -> "primary_fallback";
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
        try { primary.shutdown(); } catch (Throwable t) { AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} shutdown failed", primaryName, t); }
        try { fallback.shutdown(); } catch (Throwable t) { AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {} shutdown failed", fallbackName, t); }
    }
}
