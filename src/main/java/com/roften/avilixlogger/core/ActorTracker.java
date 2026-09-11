package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Best-effort mapping from entity UUID -> last known actor (player) who interacted with it.
 * Used to attribute removals (DISCARDED/KILLED) for non-living entities (e.g. vehicles/planes).
 */
public final class ActorTracker {

    private ActorTracker() {}

    public record ActorRef(long tsMs, UUID actorUuid, String actorName) {}

    private static final ConcurrentMap<UUID, ActorRef> ENTITY_ACTOR = new ConcurrentHashMap<>();

    /**
     * Remember the actor for an entity for a short time window.
     */
    public static void note(UUID entityUuid, UUID actorUuid, String actorName) {
        if (!LoggerConfig.isEnabled()) {
            ENTITY_ACTOR.clear();
            return;
        }
        if (entityUuid == null || actorUuid == null) return;
        ENTITY_ACTOR.put(entityUuid, new ActorRef(System.currentTimeMillis(), actorUuid, actorName));

        // Best-effort cleanup.
        if (ENTITY_ACTOR.size() > 50_000) {
            cleanup(10_000L);
        }
    }

    /**
     * Returns a recent actor reference if it is not older than maxAgeMs.
     */
    public static ActorRef getRecent(UUID entityUuid, long maxAgeMs) {
        if (!LoggerConfig.isEnabled()) {
            ENTITY_ACTOR.clear();
            return null;
        }
        if (entityUuid == null) return null;
        ActorRef r = ENTITY_ACTOR.get(entityUuid);
        if (r == null) return null;
        if ((System.currentTimeMillis() - r.tsMs) > Math.max(0L, maxAgeMs)) return null;
        return r;
    }

    /**
     * Drop old entries.
     */
    public static void cleanup(long maxAgeMs) {
        long now = System.currentTimeMillis();
        long cutoff = now - Math.max(0L, maxAgeMs);
        for (var it = ENTITY_ACTOR.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            ActorRef r = e.getValue();
            if (r == null || r.tsMs < cutoff) it.remove();
        }
    }
}
