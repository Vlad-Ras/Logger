package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

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
    private static final ConcurrentHashMap<UUID, SpatialActionWindow<Action>> RECENT = new ConcurrentHashMap<>();

    private RecentPlayerActionTracker() {}
    public static void discard(UUID player) { RECENT.remove(player); }
    public static void clear() { RECENT.clear(); }

    public static void note(ServerLevel level, ServerPlayer player, BlockPos pos) {
        note(level, player, pos, ActionKind.OTHER, player.getMainHandItem());
    }

    public static void note(ServerLevel level, ServerPlayer player, BlockPos pos, ActionKind kind, ItemStack used) {
        if (!LoggerConfig.isEnabled()) {
            RECENT.clear();
            return;
        }
        if (level == null || player == null || pos == null || CreateActorContext.isTechnicalPlayer(player)) return;
        String dim = level.dimension().location().toString();
        String itemKey = null;
        try {
            if (used != null && !used.isEmpty()) itemKey = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(used.getItem()));
        } catch (Throwable ignored) {}

        Action a = new Action(System.currentTimeMillis(), level.getGameTime(), dim, player.getUUID(), player.getName().getString(), pos.immutable(), kind, itemKey);
        SpatialActionWindow<Action> q = RECENT.computeIfAbsent(player.getUUID(),
                k -> new SpatialActionWindow<>(MAX_PER_PLAYER, Action::pos));
        synchronized (q) {
            q.add(a);
        }
    }

    /**
     * Compatibility shim for older callers.
     */
    public static ActionRef resolve(ServerLevel level, BlockPos target, int radiusBlocks, long ttlMs) {
        return resolveBest(level, target, radiusBlocks, ttlMs, null);
    }

    /** Proximity and recent unrelated actions are not evidence of causation. */
    public static ActionRef resolveBest(ServerLevel level, BlockPos target, int searchRadiusBlocks, long ttlMs, String expectedItemKey) {
        return null;
    }
}
