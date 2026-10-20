package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.api.LogAdapterRegistry;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes only near-simultaneous duplicates produced when the same action is visible through
 * both a NeoForge event and a universal mixin hook. It deliberately uses very short windows so
 * repeated intentional player actions remain separate log rows.
 */
public final class DeduplicatingLogStorage implements LogStorage {
    private static final int CLEANUP_AT = 200_000;

    private long lastCleanup;
    private long retainedBytes;
    private static final long MAX_RETAINED_BYTES = 8L * 1024 * 1024;
    private record Seen(long timestamp, long bytes) {}
    private final LogStorage delegate;
    private final ConcurrentHashMap<Fingerprint, Seen> recent = new ConcurrentHashMap<>();

    public DeduplicatingLogStorage(LogStorage delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void append(LogEntry entry) {
        if (entry == null || !LoggerConfig.isEnabled()) return;
        LogAdapterRegistry.enrich(entry);
        long window = CartAuditContext.id(entry.source) == null ? duplicateWindowMs(entry.type) : 0L;
        if (window <= 0L) {
            AdaptiveLogDiagnostics.accepted(entry);
            delegate.append(entry);
            return;
        }

        long now = entry.ts > 0L ? entry.ts : System.currentTimeMillis();
        long weight = PayloadSizeEstimator.estimate(entry);
        // Exact snapshots stay bounded; dropping cache entries only permits extra rows.
        if (retainedBytes + weight > MAX_RETAINED_BYTES) clear();
        if (weight > MAX_RETAINED_BYTES) {
            AdaptiveLogDiagnostics.accepted(entry);
            delegate.append(entry);
            return;
        }
        Fingerprint key = Fingerprint.of(entry);
        Seen previous = recent.put(key, new Seen(now, weight));
        retainedBytes += weight - (previous == null ? 0L : previous.bytes);
        if (previous != null && Math.abs(now - previous.timestamp) <= window) {
            AdaptiveLogDiagnostics.suppressedDuplicate();
            return;
        }
        AdaptiveLogDiagnostics.accepted(entry);
        delegate.append(entry);

        long wallTime = System.currentTimeMillis();
        if (recent.size() >= CLEANUP_AT || wallTime - lastCleanup >= 1_000) {
            cleanup(now - 5_000L);
            lastCleanup = wallTime;
        }
    }

    @Override
    public List<LogEntry> query(LogQuery query) {
        requireEnabled();
        return delegate.query(query);
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery query) {
        requireEnabled();
        return delegate.queryReverse(query);
    }

    @Override public boolean awaitVisible(long deadline) throws InterruptedException { return delegate.awaitVisible(deadline); }

    @Override
    public void shutdown() {
        clear();
        delegate.shutdown();
    }

    public void clear() {
        recent.clear();
        retainedBytes = 0L;
    }

    private static void requireEnabled() {
        if (!LoggerConfig.isEnabled()) {
            throw new IllegalStateException("AvilixAuthCore denied access to Logger");
        }
    }

    private void cleanup(long cutoff) {
        recent.entrySet().removeIf(entry -> entry.getValue().timestamp < cutoff);
        retainedBytes = recent.values().stream().mapToLong(Seen::bytes).sum();
    }

    private static long duplicateWindowMs(ActionType type) {
        if (type == null) return 0L;
        return switch (type) {
            case CHAT_MESSAGE -> 400L;
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_ENTITY_NBT_CHANGE -> 150L;
            case ENTITY_DEATH, ENTITY_SPAWN, PLANE_PLACE, PLANE_REMOVE -> 300L;
            default -> 0L;
        };
    }

    // This runs after background materialization. Compare exact payloads, never sampled hashes.
    private record Fingerprint(ActionType type, String dim, int x, int y, int z,
                               UUID actorUuid, String actorName, UUID entityUuid, String entityType, String entityName,
                               String source, int count, String blockBefore, String blockAfter,
                               String beBefore, String beAfter, String slotsBefore, String slotsAfter,
                               String entityNbt, String itemNbt, String invBefore, String invAfter, String extra) {
        static Fingerprint of(LogEntry e) {
            boolean chat = e.type == ActionType.CHAT_MESSAGE;
            return new Fingerprint(e.type, e.dim, chat ? 0 : e.x, chat ? 0 : e.y, chat ? 0 : e.z,
                    e.actorUuid, e.actorName, e.entityUuid, e.entityType, e.entityName, e.source, e.count,
                    e.blockBefore, e.blockAfter, e.beBefore, e.beAfter,
                    e.containerSlotsBefore, e.containerSlotsAfter, e.entityNbt, e.itemStackNbt,
                    e.playerInvBefore, e.playerInvAfter, e.extra);
        }
    }
}
