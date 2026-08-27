package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stores the exact query scope shown by the latest rollback preview.
 * Confirmation uses this immutable snapshot instead of recalculating from the player's new position.
 */
public final class RollbackPlanManager {

    public static final long PLAN_TTL_MS = 2L * 60_000L;

    public record Scope(String dimension, BlockPos min, BlockPos max) {
        public Scope {
            dimension = dimension == null ? "" : dimension;
            BlockPos a = min == null ? BlockPos.ZERO : min.immutable();
            BlockPos b = max == null ? BlockPos.ZERO : max.immutable();
            min = new BlockPos(Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()));
            max = new BlockPos(Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ()));
        }

        public long volume() {
            long sx = (long) max.getX() - min.getX() + 1L;
            long sy = (long) max.getY() - min.getY() + 1L;
            long sz = (long) max.getZ() - min.getZ() + 1L;
            try {
                return Math.multiplyExact(Math.multiplyExact(sx, sy), sz);
            } catch (ArithmeticException ignored) {
                return Long.MAX_VALUE;
            }
        }
    }

    public static final class Plan {
        private final String id;
        private final UUID playerId;
        private final long targetTs;
        private final long cutoffTs;
        private final long expiresAt;
        private final String actor;
        private final EnumSet<ActionType> types;
        private final List<Scope> scopes;

        private Plan(String id, UUID playerId, long targetTs, long cutoffTs, long expiresAt,
                     String actor, EnumSet<ActionType> types, List<Scope> scopes) {
            this.id = id;
            this.playerId = playerId;
            this.targetTs = targetTs;
            this.cutoffTs = cutoffTs;
            this.expiresAt = expiresAt;
            this.actor = actor;
            this.types = types == null ? null : types.clone();
            this.scopes = List.copyOf(scopes);
        }

        public String id() { return id; }
        public UUID playerId() { return playerId; }
        public long targetTs() { return targetTs; }
        public long cutoffTs() { return cutoffTs; }
        public long expiresAt() { return expiresAt; }
        public String actor() { return actor; }
        public EnumSet<ActionType> types() { return types == null ? null : types.clone(); }
        public List<Scope> scopes() { return scopes; }
    }

    private static final ConcurrentHashMap<UUID, Plan> PLANS = new ConcurrentHashMap<>();

    private RollbackPlanManager() {}

    public static Plan save(UUID playerId, long targetTs, long cutoffTs, String actor,
                            EnumSet<ActionType> types, List<Scope> scopes) {
        if (playerId == null || scopes == null || scopes.isEmpty()) return null;
        long now = System.currentTimeMillis();
        String id = UUID.randomUUID().toString().substring(0, 8);
        Plan plan = new Plan(id, playerId, targetTs, cutoffTs, now + PLAN_TTL_MS, actor, types, scopes);
        PLANS.put(playerId, plan);
        return plan;
    }

    public static Plan getValid(UUID playerId) {
        if (playerId == null) return null;
        Plan plan = PLANS.get(playerId);
        if (plan == null) return null;
        if (plan.expiresAt() <= System.currentTimeMillis()) {
            PLANS.remove(playerId, plan);
            return null;
        }
        return plan;
    }

    public static Plan clear(UUID playerId) {
        return playerId == null ? null : PLANS.remove(playerId);
    }
}
