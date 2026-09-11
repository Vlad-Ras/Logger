package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Best-effort attribution for delayed/decoupled entity spawns/removals.
 *
 * We record recent player actions and later score candidates when an entity spawn/remove event
 * provides no explicit actor information.
 */
public final class RecentPlayerActionTracker {

    public enum ActionKind {
        RIGHT_CLICK_BLOCK,
        RIGHT_CLICK_ITEM,
        INTERACT_ENTITY,
        ATTACK_ENTITY,
        BREAK_BLOCK,
        PLACE_BLOCK,
        OTHER
    }

    private record Action(long tsMs,
                          long tick,
                          String dim,
                          UUID actorUuid,
                          String actorName,
                          BlockPos pos,
                          ActionKind kind,
                          String itemKey) {
    }

    /**
     * Result of resolution.
     * confidence is 0..1, source is one of: context|owner_tag|recent_action|nearest
     */
    public record ActionRef(long tsMs, UUID actorUuid, String actorName, double confidence, String source) {}

    private static final int MAX_PER_PLAYER = 64;
    private static final ConcurrentHashMap<UUID, Deque<Action>> RECENT = new ConcurrentHashMap<>();

    private RecentPlayerActionTracker() {}

    public static void note(ServerLevel level, ServerPlayer player, BlockPos pos) {
        note(level, player, pos, ActionKind.OTHER, player.getMainHandItem());
    }

    public static void note(ServerLevel level, ServerPlayer player, BlockPos pos, ActionKind kind, ItemStack used) {
        if (!LoggerConfig.isEnabled()) {
            RECENT.clear();
            return;
        }
        if (level == null || player == null || pos == null) return;
        String dim = level.dimension().location().toString();
        String itemKey = null;
        try {
            if (used != null && !used.isEmpty()) itemKey = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem()));
        } catch (Throwable ignored) {}

        Action a = new Action(System.currentTimeMillis(), level.getGameTime(), dim, player.getUUID(), player.getName().getString(), pos.immutable(), kind, itemKey);
        Deque<Action> q = RECENT.computeIfAbsent(player.getUUID(), k -> new ArrayDeque<>());
        synchronized (q) {
            q.addFirst(a);
            while (q.size() > MAX_PER_PLAYER) q.removeLast();
        }
    }

    /**
     * Compatibility shim for older callers.
     */
    public static ActionRef resolve(ServerLevel level, BlockPos target, int radiusBlocks, long ttlMs) {
        return resolveBest(level, target, radiusBlocks, ttlMs, null);
    }

    public static ActionRef resolveBest(ServerLevel level, BlockPos target, int searchRadiusBlocks, long ttlMs, String expectedItemKey) {
        if (!LoggerConfig.isEnabled()) {
            RECENT.clear();
            return null;
        }
        if (level == null || target == null) return null;
        long now = System.currentTimeMillis();
        String dim = level.dimension().location().toString();

        // Collect candidates from online players in this level.
        List<ServerPlayer> players = new ArrayList<>();
        try {
            for (Player p : level.players()) {
                if (p instanceof ServerPlayer sp) players.add(sp);
            }
        } catch (Throwable ignored) {}
        if (players.isEmpty()) return null;

        Candidate best = null;
        Candidate second = null;

        for (ServerPlayer sp : players) {
            Deque<Action> q = RECENT.get(sp.getUUID());
            if (q == null) continue;
            Action bestForPlayer = null;
            double bestScoreForPlayer = 0.0;

            synchronized (q) {
                for (Action a : q) {
                    if (!dim.equals(a.dim)) continue;
                    long dt = now - a.tsMs;
                    if (dt < 0) dt = 0;
                    if (dt > ttlMs) break; // queue is newest-first

                    double dist = Math.sqrt(a.pos.distSqr(target));
                    if (dist > searchRadiusBlocks) continue;

                    double timeScore = clamp01(1.0 - (dt / 4000.0)); // 4s window
                    double distScore = clamp01(1.0 - (dist / 6.0));   // 6 block sweet spot

                    double kindBoost = switch (a.kind) {
                        case RIGHT_CLICK_BLOCK -> 0.25;
                        case ATTACK_ENTITY -> 0.25;
                        case INTERACT_ENTITY -> 0.20;
                        case RIGHT_CLICK_ITEM -> 0.15;
                        case BREAK_BLOCK -> 0.20;
                        case PLACE_BLOCK -> 0.20;
                        default -> 0.10;
                    };

                    double itemBoost = 0.0;
                    if (expectedItemKey != null && !expectedItemKey.isBlank()) {
                        if (a.itemKey != null && expectedItemKey.equals(a.itemKey)) itemBoost = 0.20;
                    }

                    double score = 0.45 * timeScore + 0.45 * distScore + kindBoost + itemBoost;
                    if (score > bestScoreForPlayer) {
                        bestScoreForPlayer = score;
                        bestForPlayer = a;
                    }
                }
            }

            if (bestForPlayer == null) continue;
            Candidate cand = new Candidate(bestForPlayer, bestScoreForPlayer);
            if (best == null || cand.score > best.score) {
                second = best;
                best = cand;
            } else if (second == null || cand.score > second.score) {
                second = cand;
            }
        }

        if (best == null) {
            // nearest fallback (single player nearby)
            return nearestFallback(level, target, 6.0);
        }

        // If top2 are too close, avoid wrong attribution.
        if (second != null && (best.score - second.score) < 0.08) {
            // Still allow nearest fallback if exactly one player is nearby.
            ActionRef nf = nearestFallback(level, target, 6.0);
            if (nf != null) return nf;
            return null;
        }

        double conf = clamp01(best.score);
        if (conf >= 0.70) {
            return new ActionRef(best.action.tsMs, best.action.actorUuid, best.action.actorName, conf, "recent_action");
        }
        if (conf >= 0.55) {
            return new ActionRef(best.action.tsMs, best.action.actorUuid, best.action.actorName, conf, "recent_action_guess");
        }

        // If not confident enough, try nearest single-player fallback.
        ActionRef nf = nearestFallback(level, target, 6.0);
        return nf;
    }

    private static ActionRef nearestFallback(ServerLevel level, BlockPos target, double maxDist) {
        try {
            ServerPlayer nearest = null;
            double best = Double.MAX_VALUE;
            int within = 0;
            for (Player p : level.players()) {
                if (!(p instanceof ServerPlayer sp)) continue;
                double d = Math.sqrt(sp.blockPosition().distSqr(target));
                if (d <= maxDist) {
                    within++;
                    if (d < best) {
                        best = d;
                        nearest = sp;
                    }
                }
            }
            // only if single player nearby to reduce errors
            if (nearest != null && within == 1) {
                double conf = clamp01(0.55 - (best / (maxDist * 2.0))); // ~0.55..0.30
                return new ActionRef(System.currentTimeMillis(), nearest.getUUID(), nearest.getName().getString(), conf, "nearest");
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private record Candidate(Action action, double score) {}

    private static double clamp01(double v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}
