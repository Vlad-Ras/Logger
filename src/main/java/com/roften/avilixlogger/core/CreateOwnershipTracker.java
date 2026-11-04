package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MinecartItem;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Best-effort attribution for Create contraptions / Schematicannon / minecart drill.
 *
 * Goals:
 * - when world changes are performed by non-player actors (SYSTEM/FakePlayer/contraptions),
 *   try to resolve the player who placed/activated the Create device.
 *
 * This intentionally avoids a hard dependency on Create. It uses registry namespaces ("create")
 * and a few heuristics.
 */
public final class CreateOwnershipTracker {

    private CreateOwnershipTracker() {}

    private static final String OWNER_TAG = "avilixlogger_owner";
    private static final String OWNER_NAME_TAG = "avilixlogger_owner_name";

    private static final long INTERACT_TTL_MS = 2 * 60_000L; // 2 minutes
    private static final int INTERACT_RADIUS = 24;

    private record ChunkKey(String dim, int x, int z) {}

    public record ResolvedActor(UUID uuid, String name, String source) {}

    private static final ConcurrentMap<ChunkKey, ConcurrentHashMap<Long, ActorTracker.ActorRef>> LAST_INTERACT = new ConcurrentHashMap<>();

    private static final ConcurrentMap<UUID, PendingMinecart> PENDING_MINECART = new ConcurrentHashMap<>();
    private record PendingMinecart(String dim, BlockPos pos, long gameTime, String playerName) {}

    private static boolean isCreateBlock(ServerLevel level, BlockPos pos) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
        return id != null && "create".equals(id.getNamespace());
    }

    private static boolean isCreateEntity(Entity e) {
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return id != null && "create".equals(id.getNamespace());
    }

    /** Call from player right-click handlers. */
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player p = event.getEntity();
        if (p == null) return;

        BlockPos pos = event.getPos();

        // Track interactions with Create devices.
        if (isCreateBlock(level, pos) || (level.getBlockEntity(pos) != null && level.getBlockEntity(pos).getClass().getName().toLowerCase().contains("create"))) {
            ChunkKey key = new ChunkKey(level.dimension().location().toString(), pos.getX() >> 4, pos.getZ() >> 4);
            ActorTracker.ActorRef actor = new ActorTracker.ActorRef(System.currentTimeMillis(), p.getUUID(), p.getName().getString());
            LAST_INTERACT.compute(key, (ignored, entries) -> {
                if (entries == null) entries = new ConcurrentHashMap<>();
                entries.put(pos.asLong(), actor);
                return entries;
            });
        }

        // Track minecart placement intent (minecart drill uses minecart base).
        ItemStack stack = event.getItemStack();
        if (!stack.isEmpty() && (stack.getItem() instanceof MinecartItem
                || (BuiltInRegistries.ITEM.getKey(stack.getItem()) != null
                    && "create".equals(BuiltInRegistries.ITEM.getKey(stack.getItem()).getNamespace())
                    && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains("minecart")))) {
            PENDING_MINECART.put(p.getUUID(),
                    new PendingMinecart(level.dimension().location().toString(), pos, level.getGameTime(), p.getName().getString()));
        }
    }

    /** Call from EntityJoinLevelEvent to attach owner to minecarts. */
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity e = event.getEntity();
        if (!(e instanceof AbstractMinecart mc)) return;

        // If already tagged, do nothing.
        if (mc.getPersistentData().contains(OWNER_TAG)) return;

        long nowTick = level.getGameTime();
        PendingMinecart best = null;
        UUID bestPlayer = null;
        double bestDist2 = Double.MAX_VALUE;

        for (var ent : PENDING_MINECART.entrySet()) {
            UUID playerUuid = ent.getKey();
            PendingMinecart pm = ent.getValue();
            if (!pm.dim().equals(level.dimension().location().toString())) continue;
            if (nowTick - pm.gameTime() > 5) continue;

            double dx = mc.getX() - (pm.pos().getX() + 0.5);
            double dy = mc.getY() - (pm.pos().getY() + 0.5);
            double dz = mc.getZ() - (pm.pos().getZ() + 0.5);
            double d2 = dx*dx + dy*dy + dz*dz;
            if (d2 <= 4.0 && d2 < bestDist2) { // within 2 blocks
                bestDist2 = d2;
                best = pm;
                bestPlayer = playerUuid;
            }
        }

        if (best != null && bestPlayer != null) {
            mc.getPersistentData().putString(OWNER_TAG, bestPlayer.toString());
            mc.getPersistentData().putString(OWNER_NAME_TAG, best.playerName());
        }
    }

    @Nullable
    public static ResolvedActor resolveForSystemChange(ServerLevel level, BlockPos changedPos, @Nullable Entity directCauser) {

        // Ownership and nearby interactions describe history, not the actor of this mutation.
        return null;
    }

    public static void cleanupBackground(long now) {
        for (ChunkKey key : LAST_INTERACT.keySet()) {
            LAST_INTERACT.computeIfPresent(key, (ignored, entries) -> {
                entries.entrySet().removeIf(entry -> now - entry.getValue().tsMs() > INTERACT_TTL_MS);
                return entries.isEmpty() ? null : entries;
            });
        }
    }

    @Nullable
    private static UUID readOwnerUuid(Entity e) {
        try {
            String s = e.getPersistentData().getString(OWNER_TAG);
            if (s != null && !s.isBlank()) return UUID.fromString(s);
        } catch (Throwable ignored) {}
        return null;
    }

    @Nullable
    private static String readOwnerName(Entity e) {
        try {
            String s = e.getPersistentData().getString(OWNER_NAME_TAG);
            if (s != null && !s.isBlank()) return s;
        } catch (Throwable ignored) {}
        return null;
    }
}
