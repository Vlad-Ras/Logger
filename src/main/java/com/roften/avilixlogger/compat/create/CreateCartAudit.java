package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Event-driven mounted-contraption audit. All live-object reads stay on the server thread. */
public final class CreateCartAudit {
    private static final ThreadLocal<Boolean> METADATA_ONLY = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<CartAuditState> ASSEMBLING = new ThreadLocal<>();
    private static final Map<UUID, Entity> LOADED = new HashMap<>();
    private CreateCartAudit() {}
    public static boolean enabled() { return !CartAuditContext.restoring() && LoggerConfig.isEnabled(); }
    public static CartAuditState state(Entity e) {
        return e instanceof CartEntityAccess a ? a.avilixlogger$mountedState() : null;
    }
    public static boolean metadataOnly() { return METADATA_ONLY.get(); }
    public static CartAuditState assembling(CartAuditState value) { var old = ASSEMBLING.get(); ASSEMBLING.set(value); return old; }
    public static CartAuditState assembling() { return ASSEMBLING.get(); }
    public static Entity loaded(UUID id) { Entity e = LOADED.get(id); return e != null && !e.isRemoved() ? e : null; }
    public static void clear() { CartStorageAudit.clear(); LOADED.clear(); ASSEMBLING.remove(); METADATA_ONLY.remove(); }
    public static OrientedContraptionEntity passenger(AbstractMinecart cart) {
        for (Entity e : cart.getPassengers()) if (e instanceof OrientedContraptionEntity oce && state(e) != null) return oce;
        return null;
    }
    public static OrientedContraptionEntity target(Entity e) {
        if (e instanceof AbstractMinecart c) return passenger(c);
        return e instanceof OrientedContraptionEntity oce && state(e) != null ? oce : null;
    }
    public static void owner(CartAuditState s, Player player) {
        if (player == null || player instanceof net.neoforged.neoforge.common.util.FakePlayer) return;
        s.owner = player.getUUID(); s.ownerName = player.getName().getString();
    }
    public static CartAuditState forAssembly(ServerLevel level, BlockPos pos, AbstractMinecart cart) {
        CartAuditState s = new CartAuditState();
        CompoundTag old = cart.getPersistentData().getCompound(CartAuditState.KEY);
        if (old.hasUUID("Id")) s.read(old);
        CauseContext.Cause cause = CauseContext.peek();
        if (cause != null && cause.actorUuid() != null) {
            s.owner = cause.actorUuid(); s.ownerName = cause.actorName();
        } else {
            // Only a recent action AT the assembler may override a durable placer. No nearby-player guess.
            var action = RecentPlayerActionTracker.resolve(level, pos, 0, 5_000L);
            if (action != null && action.actorUuid() != null) { s.owner = action.actorUuid(); s.ownerName = action.actorName(); }
            else if (s.owner == null) {
                CompoundTag data = cart.getPersistentData();
                for (String key : new String[]{"avilixlogger_owner", "avilixlogger_owner_uuid"}) {
                    try { s.owner = UUID.fromString(data.getString(key)); break; } catch (IllegalArgumentException ignored) {}
                }
                s.ownerName = data.getString("avilixlogger_owner_name");
                if (s.owner == null && level.getBlockEntity(pos) != null) {
                    CompoundTag placer = level.getBlockEntity(pos).getPersistentData().getCompound("AvilixCartPlacer");
                    if (placer.hasUUID("Owner")) { s.owner = placer.getUUID("Owner"); s.ownerName = placer.getString("Name"); }
                }
            }
        }
        s.sequence++;
        return s;
    }
    public static void attach(OrientedContraptionEntity e) {
        CartAuditState s = state(e); if (s == null) return;
        LOADED.put(s.id, e); s.joined = true; CartStorageAudit.bind(e);
        if (e.getVehicle() instanceof AbstractMinecart cart) cart.getPersistentData().put(CartAuditState.KEY, s.write());
    }
    /** No serialization, inventory scan, nearby-entity search or allocation on unchanged ticks. */
    public static void afterTick(OrientedContraptionEntity e) {
        CartAuditState s = state(e); if (s == null || s.locked || !(e.level() instanceof ServerLevel level)) return;
        if (!s.joined) { attach(e); emit(e, ActionType.CART_LOAD, "load", null, pose(e), "loaded"); }
        Entity cart = e.getVehicle();
        boolean moving = cart != null && (cart.getDeltaMovement().lengthSqr() > 0.000001
                || Math.abs(cart.getX() - cart.xo) + Math.abs(cart.getZ() - cart.zo) > 0.0001);
        String status = e.isStalled() ? "stalled" : cart == null ? "detached" : moving ? "moving" : "stopped";
        if (!status.equals(s.status)) {
            String prior = s.status; s.status = status;
            emit(e, ActionType.CART_STATUS, "status", null, pose(e), "status=" + status + "; previous=" + prior);
        }
        int cx = e.blockPosition().getX() >> 4, cz = e.blockPosition().getZ() >> 4;
        // Position checkpoints every five seconds while moving, and on chunk crossings. No full NBT.
        if (cx != s.chunkX || cz != s.chunkZ || moving && level.getGameTime() - s.lastPositionTick >= 100) {
            s.chunkX = cx; s.chunkZ = cz; s.lastPositionTick = level.getGameTime();
            emit(e, ActionType.CART_MOVE, "position", null, pose(e), "position");
        }
    }
    public static CompoundTag pose(Entity e) {
        CompoundTag n = new CompoundTag();
        n.putString("Dimension", e.level().dimension().location().toString()); n.putUUID("Entity", e.getUUID());
        ListTag pos = new ListTag(); pos.add(net.minecraft.nbt.DoubleTag.valueOf(e.getX())); pos.add(net.minecraft.nbt.DoubleTag.valueOf(e.getY())); pos.add(net.minecraft.nbt.DoubleTag.valueOf(e.getZ())); n.put("Pos", pos);
        if (e instanceof OrientedContraptionEntity oce) { n.putFloat("Yaw", oce.yaw); n.putFloat("Pitch", oce.pitch); }
        return n;
    }
    /** Save metadata once, then reuse an already-serialized Create payload when packing. */
    public static CompoundTag snapshot(OrientedContraptionEntity e, CompoundTag existingContraption) {
        CompoundTag out = new CompoundTag(); out.putInt("Format", 1); out.putString("Form", "entity");
        out.putString("Dimension", e.level().dimension().location().toString()); out.put(CartAuditState.KEY, state(e).write());
        boolean old = METADATA_ONLY.get(); METADATA_ONLY.set(existingContraption != null);
        try {
            CompoundTag entity = save(e);
            if (existingContraption != null) entity.put("Contraption", existingContraption);
            out.put("Entity", entity);
            // Only the base cart's own payload, not another copy of all contraption passengers.
            METADATA_ONLY.set(true);
            if (e.getVehicle() instanceof AbstractMinecart cart) {
                CompoundTag base = save(cart); base.remove("Passengers"); out.put("Cart", base);
            }
        } finally { METADATA_ONLY.set(old); }
        // Create's writer references live BE tags. Freeze them before crossing the queue boundary.
        return out.copy();
    }
    public static CompoundTag save(Entity e) {
        CompoundTag n = new CompoundTag(); n.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
        e.saveWithoutId(n); return n;
    }
    public static CompoundTag form(OrientedContraptionEntity e, String form) {
        CompoundTag n = pose(e); n.putInt("Format", 1); n.putString("Form", form); n.put(CartAuditState.KEY, state(e).write()); return n;
    }
    public static CompoundTag itemForm(OrientedContraptionEntity e, ItemStack item, Player holder) {
        CompoundTag n = form(e, "item");
        n.put("Item", NbtSerde.snapshotItemStack(item, e.registryAccess()));
        if (holder != null) { n.putUUID("Holder", holder.getUUID()); n.putString("HolderName", holder.getName().getString()); }
        return n;
    }
    public static UUID itemId(ItemStack item) {
        CompoundTag n = item.get(AllDataComponents.MINECRAFT_CONTRAPTION_DATA);
        if (n == null) return null;
        CompoundTag a = n.getCompound(CartAuditState.KEY); return a.hasUUID("Id") ? a.getUUID("Id") : null;
    }
    public static void emit(OrientedContraptionEntity e, ActionType type, String phase, CompoundTag before, CompoundTag after, String detail) {
        if (!enabled() || !(e.level() instanceof ServerLevel level)) return;
        CartAuditState s = state(e); if (s == null) return;
        LogEntry row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = type;
        row.dim = level.dimension().location().toString(); row.x = e.blockPosition().getX(); row.y = e.blockPosition().getY(); row.z = e.blockPosition().getZ();
        row.actorUuid = s.owner; row.actorName = s.ownerName; row.entityUuid = e.getUUID(); row.entityType = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        CauseContext.Cause actor = CauseContext.peek();
        if (actor != null && actor.actorUuid() != null) { row.actorUuid = actor.actorUuid(); row.actorName = actor.actorName(); }
        row.source = s.stamp(phase).source();
        row.extra = "cart=" + s.id + "; owner=" + s.ownerName + "; sequence=" + s.sequence + "; " + detail;
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, before); row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, after);
        LogIdGenerator.ensure(row); LoggerRuntime.storage(level).append(row);
    }
    public static void removed(OrientedContraptionEntity e, Entity.RemovalReason reason) {
        CartAuditState s = state(e); if (s == null) return;
        LOADED.remove(s.id, e);
        if (s.removing || CartAuditContext.restoring()) return;
        if (reason == Entity.RemovalReason.UNLOADED_TO_CHUNK || reason == Entity.RemovalReason.UNLOADED_WITH_PLAYER) {
            emit(e, ActionType.CART_UNLOAD, "unload", null, pose(e), "reason=" + reason); s.joined = false;
        } else {
            s.sequence++;
            emit(e, ActionType.CART_REMOVE, "remove", snapshot(e, null), form(e, "removed"), "reason=" + reason);
        }
    }
    public static void failed(String operation, Throwable error) {
        AvilixLoggerMod.LOGGER.error("[AvilixLogger] Create cart audit failed during {}", operation, error);
    }
}
