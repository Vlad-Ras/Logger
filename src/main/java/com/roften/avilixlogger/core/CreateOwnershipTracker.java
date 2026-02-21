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

    private record PosKey(String dim, long posLong) {}

    public record ResolvedActor(UUID uuid, String name, String source) {}

    private static final ConcurrentMap<PosKey, ActorTracker.ActorRef> LAST_INTERACT = new ConcurrentHashMap<>();

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
            LAST_INTERACT.put(new PosKey(level.dimension().location().toString(), pos.asLong()),
                    new ActorTracker.ActorRef(System.currentTimeMillis(), p.getUUID(), p.getName().getString()));
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

        // 1) If the direct causer is a Create entity and has owner tag, use it.
        if (directCauser != null && isCreateEntity(directCauser)) {
            UUID u = readOwnerUuid(directCauser);
            if (u != null) {
                String n = readOwnerName(directCauser);
                return new ResolvedActor(u, n, "create:entity@" + directCauser.blockPosition().getX() + "," + directCauser.blockPosition().getY() + "," + directCauser.blockPosition().getZ());
            }
        }

        // 2) Find nearby Create entities with owner tag.
        var aabb = new net.minecraft.world.phys.AABB(
                changedPos.getX() - 16, changedPos.getY() - 16, changedPos.getZ() - 16,
                changedPos.getX() + 16, changedPos.getY() + 16, changedPos.getZ() + 16
        );
        for (Entity e : level.getEntities(null, aabb)) {
            if (!isCreateEntity(e)) continue;
            UUID u = readOwnerUuid(e);
            if (u == null) continue;
            String n = readOwnerName(e);
            return new ResolvedActor(u, n, "create:entity@" + e.blockPosition().getX() + "," + e.blockPosition().getY() + "," + e.blockPosition().getZ());
        }

        // 3) Check for tagged minecarts (covers minecart drill).
        for (Entity e : level.getEntities(null, aabb)) {
            if (!(e instanceof AbstractMinecart)) continue;
            UUID u = readOwnerUuid(e);
            if (u == null) continue;
            String n = readOwnerName(e);
            return new ResolvedActor(u, n, "create:minecart@" + e.blockPosition().getX() + "," + e.blockPosition().getY() + "," + e.blockPosition().getZ());
        }

        // 4) Search around for a recent Create block interaction.
        long now = System.currentTimeMillis();
        String dim = level.dimension().location().toString();
        BlockPos.MutableBlockPos mp = new BlockPos.MutableBlockPos();

        int bestD2 = Integer.MAX_VALUE;
        ActorTracker.ActorRef best = null;
        BlockPos bestPos = null;

        for (int dx = -INTERACT_RADIUS; dx <= INTERACT_RADIUS; dx++) {
            for (int dz = -INTERACT_RADIUS; dz <= INTERACT_RADIUS; dz++) {
                // keep it cheap: ignore far corners early
                int d2 = dx*dx + dz*dz;
                if (d2 > INTERACT_RADIUS*INTERACT_RADIUS) continue;
                mp.set(changedPos.getX() + dx, changedPos.getY(), changedPos.getZ() + dz);

                ActorTracker.ActorRef ar = LAST_INTERACT.get(new PosKey(dim, mp.asLong()));
                if (ar == null) continue;
                if (now - ar.tsMs() > INTERACT_TTL_MS) continue;

                if (d2 < bestD2) {
                    bestD2 = d2;
                    best = ar;
                    bestPos = mp.immutable();
                }
            }
        }

        if (best != null && best.actorUuid() != null) {
            String n = best.actorName();
            String src = "create:block@" + bestPos.getX() + "," + bestPos.getY() + "," + bestPos.getZ();
            return new ResolvedActor(best.actorUuid(), n, src);
        }

        return null;
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
