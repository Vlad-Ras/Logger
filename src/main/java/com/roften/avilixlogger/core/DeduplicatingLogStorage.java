package com.roften.avilixlogger.core;

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

    private final LogStorage delegate;
    private final ConcurrentHashMap<Fingerprint, Long> recent = new ConcurrentHashMap<>();

    public DeduplicatingLogStorage(LogStorage delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public void append(LogEntry entry) {
        if (entry == null) return;
        LogAdapterRegistry.enrich(entry);
        long window = duplicateWindowMs(entry.type);
        if (window <= 0L) {
            AdaptiveLogDiagnostics.accepted(entry);
            delegate.append(entry);
            return;
        }

        long now = entry.ts > 0L ? entry.ts : System.currentTimeMillis();
        Fingerprint key = Fingerprint.of(entry);
        Long previous = recent.put(key, now);
        if (previous != null && now >= previous && (now - previous) <= window) {
            AdaptiveLogDiagnostics.suppressedDuplicate();
            return;
        }
        AdaptiveLogDiagnostics.accepted(entry);
        delegate.append(entry);

        if (recent.size() >= CLEANUP_AT) cleanup(now - 5_000L);
    }

    @Override
    public List<LogEntry> query(LogQuery query) {
        return delegate.query(query);
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery query) {
        return delegate.queryReverse(query);
    }

    @Override
    public void shutdown() {
        recent.clear();
        delegate.shutdown();
    }

    public void clear() {
        recent.clear();
    }

    private void cleanup(long cutoff) {
        recent.entrySet().removeIf(entry -> entry.getValue() == null || entry.getValue() < cutoff);
    }

    private static long duplicateWindowMs(ActionType type) {
        if (type == null) return 0L;
        return switch (type) {
            case CHAT_MESSAGE -> 400L;
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_ENTITY_NBT_CHANGE,
                    CONTAINER_PUT, CONTAINER_TAKE -> 150L;
            case ENTITY_DEATH, ENTITY_SPAWN, PLANE_PLACE, PLANE_REMOVE -> 300L;
            default -> 0L;
        };
    }

    private record Fingerprint(ActionType type, String dim, int x, int y, int z,
                               UUID actorUuid, UUID entityUuid, String entityType,
                               int beforeHash, int afterHash, int payloadHash, int extraHash) {
        static Fingerprint of(LogEntry entry) {
            boolean chat = entry.type == ActionType.CHAT_MESSAGE;
            boolean block = entry.type == ActionType.BLOCK_BREAK || entry.type == ActionType.BLOCK_PLACE
                    || entry.type == ActionType.BLOCK_INTERACT || entry.type == ActionType.BLOCK_ENTITY_NBT_CHANGE;
            boolean entity = entry.type == ActionType.ENTITY_DEATH || entry.type == ActionType.ENTITY_SPAWN
                    || entry.type == ActionType.PLANE_PLACE || entry.type == ActionType.PLANE_REMOVE;
            return new Fingerprint(entry.type, entry.dim, chat ? 0 : entry.x, chat ? 0 : entry.y, chat ? 0 : entry.z,
                    entry.actorUuid, entry.entityUuid, entry.entityType,
                    stableHash(entry.blockBefore, entry.beBefore, entry.containerSlotsBefore),
                    block || entity || chat ? 0 : stableHash(entry.blockAfter, entry.beAfter, entry.containerSlotsAfter),
                    block || entity || chat ? 0 : stableHash(entry.entityNbt, entry.itemStackNbt, entry.playerInvBefore, entry.playerInvAfter),
                    chat ? stableHash(entry.extra) : (block || entity ? 0 : stableHash(entry.extra)));
        }

        private static int stableHash(String... values) {
            int hash = 1;
            if (values == null) return hash;
            for (String value : values) {
                hash = 31 * hash + sampledHash(value);
                hash = 31 * hash + (value == null ? 0 : value.length());
            }
            return hash;
        }

        /** Avoid rescanning multi-megabyte modded NBT on the server thread just for deduplication. */
        private static int sampledHash(String value) {
            if (value == null) return 0;
            int length = value.length();
            if (length <= 256) return value.hashCode();
            int hash = length;
            int samples = 32;
            for (int i = 0; i < samples; i++) {
                int index = (int) (((long) i * (length - 1)) / (samples - 1));
                hash = 31 * hash + value.charAt(index);
            }
            return hash;
        }
    }
}
