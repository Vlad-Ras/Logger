package com.roften.avilixlogger.compat.airplanes;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.NbtSerde;
import com.roften.avilixlogger.core.ActorTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerLevel;

import java.util.UUID;

/**
 * Compat helpers for Immersive Aircraft / Man of Many Planes.
 *
 * This module is intentionally dependency-free: mixins call into these hooks.
 */
public final class AirplanesCompatHooks {

    private AirplanesCompatHooks() {}

    public static final String OWNER_KEY = "owner";
    public static final String OWNER_UUID_KEY = "owner_uuid";
    public static final String OWNER_NAME_KEY = "owner_name";

    /**
     * Writes owner into ItemStack custom data so it can be transferred into the spawned plane entity.
     */
    public static void ensureOwnerOnItemStack(ItemStack stack, Player player) {
        if (stack == null || stack.isEmpty() || player == null) return;
        try {
            CompoundTag tag = new CompoundTag();
            if (stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA) != null) {
                tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            }
            // Prefer UUID: stable even if player changes nickname.
            if (!tag.contains(OWNER_UUID_KEY) && !tag.contains(OWNER_KEY)) {
                tag.putString(OWNER_KEY, player.getUUID().toString());
                tag.putString(OWNER_UUID_KEY, player.getUUID().toString());
            }
            // Keep a name hint for convenience.
            if (!tag.contains(OWNER_NAME_KEY)) {
                tag.putString(OWNER_NAME_KEY, player.getName().getString());
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {
        }
    }

    /** Best-effort: whether the given stack is an Immersive Aircraft vehicle item (no hard dependency). */
    public static boolean isPlaneItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        try {
            Class<?> vehicleItem = Class.forName("immersive_aircraft.item.VehicleItem");
            return vehicleItem.isInstance(stack.getItem());
        } catch (Throwable ignored) {
            return false;
        }
    }



    /** Best-effort: whether the given entity is an Immersive Aircraft vehicle entity (no hard dependency). */
    public static boolean isPlaneEntity(Entity e) {
        if (e == null) return false;
        try {
            Class<?> vehicleEntity = Class.forName("immersive_aircraft.entity.VehicleEntity");
            if (vehicleEntity.isInstance(e)) return true;
        } catch (Throwable ignored) {
        }
        // Man of Many Planes entities extend IA aircraft types.
        try {
            Class<?> aircraft = Class.forName("immersive_aircraft.entity.AircraftEntity");
            if (aircraft.isInstance(e)) return true;
        } catch (Throwable ignored) {
        }
        try {
            Class<?> airplane = Class.forName("immersive_aircraft.entity.AirplaneEntity");
            if (airplane.isInstance(e)) return true;
        } catch (Throwable ignored) {
        }
        // Fallback for other plane mods: rely on owner keys in NBT.
        try {
            var t = new net.minecraft.nbt.CompoundTag();
            e.saveWithoutId(t);
            return t.contains(OWNER_UUID_KEY) || t.contains(OWNER_KEY) || t.contains(OWNER_NAME_KEY);
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Reads owner info from an entity's NBT. Returns null if absent.
     * Keys supported: owner_uuid, owner, owner_name (and some common variants).
     */
    public static CompoundTag getOwnerTag(Entity e) {
        if (e == null) return null;
        try {
            CompoundTag t = new CompoundTag();
            e.saveWithoutId(t);
            // copy only relevant keys to keep output small
            CompoundTag out = new CompoundTag();
            if (t.contains(OWNER_UUID_KEY)) out.putString(OWNER_UUID_KEY, t.getString(OWNER_UUID_KEY));
            if (t.contains(OWNER_KEY)) out.putString(OWNER_KEY, t.getString(OWNER_KEY));
            if (t.contains(OWNER_NAME_KEY)) out.putString(OWNER_NAME_KEY, t.getString(OWNER_NAME_KEY));

            // common variants seen in some forks
            if (out.isEmpty()) {
                if (t.contains("OwnerUUID")) out.putString(OWNER_UUID_KEY, t.getString("OwnerUUID"));
                if (t.contains("Owner")) out.putString(OWNER_NAME_KEY, t.getString("Owner"));
                if (t.contains("ownerUuid")) out.putString(OWNER_UUID_KEY, t.getString("ownerUuid"));
                if (t.contains("ownerUUID")) out.putString(OWNER_UUID_KEY, t.getString("ownerUUID"));
            }

            return out.isEmpty() ? null : out;
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** Extracts a UUID string (if any) from an owner tag. */
    public static String ownerUuidFromTag(CompoundTag tag) {
        if (tag == null) return null;
        try {
            if (tag.contains(OWNER_UUID_KEY)) {
                String v = tag.getString(OWNER_UUID_KEY);
                return v == null || v.isBlank() ? null : v;
            }
            if (tag.contains(OWNER_KEY)) {
                String v = tag.getString(OWNER_KEY);
                return v == null || v.isBlank() ? null : v;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /** Extracts a name string (if any) from an owner tag. */
    public static String ownerNameFromTag(CompoundTag tag) {
        if (tag == null) return null;
        try {
            if (tag.contains(OWNER_NAME_KEY)) {
                String v = tag.getString(OWNER_NAME_KEY);
                return v == null || v.isBlank() ? null : v;
            }
        } catch (Throwable ignored) {}
        return null;
    }
    /** Reads owner info from the stack's CUSTOM_DATA. Returns null if absent. */
    public static CompoundTag getOwnerTag(ItemStack stack) {
        try {
            if (stack == null || stack.isEmpty()) return null;
            if (!stack.has(DataComponents.CUSTOM_DATA) || stack.get(DataComponents.CUSTOM_DATA) == null) return null;
            CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            if (tag.contains(OWNER_UUID_KEY) || tag.contains(OWNER_KEY) || tag.contains(OWNER_NAME_KEY)) return tag;
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Sets owner metadata on the plane item.
     * If ownerUuid is null/blank, only owner_name is stored (for offline/manual assignments).
     */
    public static void setOwnerOnItem(ItemStack stack, String ownerName, String ownerUuid) {
        if (stack == null || stack.isEmpty()) return;
        try {
            CompoundTag tag = new CompoundTag();
            if (stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA) != null) {
                tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            }

            // clear existing
            tag.remove(OWNER_KEY);
            tag.remove(OWNER_UUID_KEY);
            tag.remove(OWNER_NAME_KEY);

            if (ownerName != null && !ownerName.isBlank()) {
                tag.putString(OWNER_NAME_KEY, ownerName);
            }
            if (ownerUuid != null && !ownerUuid.isBlank()) {
                tag.putString(OWNER_KEY, ownerUuid);
                tag.putString(OWNER_UUID_KEY, ownerUuid);
            }
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {}
    }

    /** Clears owner metadata from the plane item. */
    public static void clearOwnerOnItem(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        try {
            if (!stack.has(DataComponents.CUSTOM_DATA) || stack.get(DataComponents.CUSTOM_DATA) == null) return;
            CompoundTag tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            tag.remove(OWNER_KEY);
            tag.remove(OWNER_UUID_KEY);
            tag.remove(OWNER_NAME_KEY);
            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        } catch (Throwable ignored) {}
    }

    /**
     * Marks a plane (entity uuid) as acted by a player right now.
     * LoggerEventHandlers will use this as the primary actor source for removal logs.
     */
    public static void markRecentActor(Entity planeEntity, Player player) {
        if (planeEntity == null || player == null) return;
        try {
            ActorTracker.note(planeEntity.getUUID(), player.getUUID(), player.getName().getString());
        } catch (Throwable ignored) {
        }
    }

    /**
     * Optional immediate log for placement (some servers want this even if entity spawn gets logged).
     */
    public static void logPlacement(ServerLevel level, Player player, ItemStack usedStack) {
        if (level == null || player == null || usedStack == null || usedStack.isEmpty()) return;
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;

        try {
            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = level.dimension().location().toString();
            e.type = ActionType.PLANE_PLACE; // AirPlanesLogger parity
            e.actorUuid = player.getUUID();
            e.actorName = player.getName().getString();
            var pos = player.blockPosition();
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();
            e.itemStackNbt = NbtSerde.writeItemStack(usedStack, level.registryAccess());
            e.extra = "plane place " + usedStack.getDescriptionId();
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {
        }
    }

    /** Log plane removal (break/pickup) as an explicit action, independent from ENTITY_REMOVE. */
    public static void logRemoval(ServerLevel level, Player player, Entity planeEntity, String reason) {
        if (level == null || player == null || planeEntity == null) return;
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        try {
            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = level.dimension().location().toString();
            e.type = ActionType.PLANE_REMOVE;
            e.actorUuid = player.getUUID();
            e.actorName = player.getName().getString();
            var pos = planeEntity.blockPosition();
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();
            e.entityType = planeEntity.getType().toString();
            e.entityUuid = planeEntity.getUUID();
            e.extra = (reason == null || reason.isBlank()) ? ("plane remove " + e.entityType)
                    : ("plane remove " + e.entityType + " (" + reason + ")");
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    /** Log plane mount as an explicit action. */
    public static void logMount(ServerLevel level, Player player, Entity planeEntity) {
        if (level == null || player == null || planeEntity == null) return;
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        try {
            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = level.dimension().location().toString();
            e.type = ActionType.PLANE_MOUNT;
            e.actorUuid = player.getUUID();
            e.actorName = player.getName().getString();
            var pos = planeEntity.blockPosition();
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();
            e.entityType = planeEntity.getType().toString();
            e.entityUuid = planeEntity.getUUID();
            e.extra = "plane mount " + e.entityType;
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }


    // --- Placement attribution (solve "placer != owner" problem) ---
    private static final java.util.concurrent.ConcurrentHashMap<java.util.UUID, Placement> RECENT_PLACERS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PLACER_TTL_MS = 2500L;

    private record Placement(long ts, String dim, BlockPos pos, java.util.UUID actorUuid, String actorName) {}

    public static void notePlaneItemUse(ServerLevel level, Player player) {
        if (level == null || player == null) return;
        try {
            RECENT_PLACERS.put(player.getUUID(), new Placement(System.currentTimeMillis(), level.dimension().location().toString(), player.blockPosition(), player.getUUID(), player.getName().getString()));
        } catch (Throwable ignored) {}
    }

    /**
     * If a plane entity appears right after a player used a plane item, attribute the spawn to that player.
     */
    public static ActorTracker.ActorRef consumeRecentPlacer(ServerLevel level, Entity planeEntity) {
        if (level == null || planeEntity == null) return null;
        try {
            long now = System.currentTimeMillis();
            String dim = level.dimension().location().toString();
            BlockPos ep = planeEntity.blockPosition();
            Placement best = null;
            java.util.UUID bestKey = null;
            for (var ent : RECENT_PLACERS.entrySet()) {
                Placement p = ent.getValue();
                if (p == null) continue;
                if (now - p.ts > PLACER_TTL_MS) continue;
                if (!dim.equals(p.dim)) continue;
                if (p.pos.distManhattan(ep) > 6) continue;
                best = p;
                bestKey = ent.getKey();
                break;
            }
            if (bestKey != null) RECENT_PLACERS.remove(bestKey);
            if (best != null) {
                ActorTracker.note(planeEntity.getUUID(), best.actorUuid, best.actorName);
                return new ActorTracker.ActorRef(now, best.actorUuid, best.actorName);
            }
        } catch (Throwable ignored) {}
        return null;
    }

}