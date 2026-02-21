package com.roften.avilixlogger.core;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores a short-lived "cause" string for entity removals (primarily planes).
 *
 * We capture it in mixins (hurt/applyDamage) and read it when the entity leaves the world.
 */
public final class CauseTracker {

    private static final Map<UUID, Stamp> CAUSES = new ConcurrentHashMap<>();
    private static final long TTL_MS = 10_000L;

    private CauseTracker() {}

    public static void note(UUID entityUuid, String cause) {
        if (entityUuid == null || cause == null || cause.isBlank()) return;
        CAUSES.put(entityUuid, new Stamp(System.currentTimeMillis(), cause));
    }

    public static String pop(UUID entityUuid) {
        if (entityUuid == null) return null;
        Stamp s = CAUSES.remove(entityUuid);
        if (s == null) return null;
        long age = System.currentTimeMillis() - s.ts;
        if (age > TTL_MS) return null;
        return s.cause;
    }

    private record Stamp(long ts, String cause) {}
}
