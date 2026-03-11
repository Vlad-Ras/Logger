package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.lang.reflect.Method;

/**
 * Core capture layer. Designed to be server-side and mod-agnostic.
 */
public final class LoggerEventHandlers {

    /**
     * Dedup for noisy pickup events (some stacks/mods fire ItemEntityPickupEvent.Pre multiple times
     * per single logical pickup).
     * Key format: playerUUID:itemEntityUUID
     */
    private static final Map<String, Long> RECENT_PICKUPS = new ConcurrentHashMap<>();

    /**
     * Dedup for drop/toss logging.
     * Key format: playerUUID:itemEntityUUID
     */
    private static final Map<String, Long> RECENT_DROPS = new ConcurrentHashMap<>();

    private static boolean shouldLogRecent(Map<String, Long> map, String key, long nowMs, long windowMs) {
        if (key == null) return true;
        Long prev = map.get(key);
        if (prev != null && (nowMs - prev) >= 0 && (nowMs - prev) < windowMs) {
            return false;
        }
        map.put(key, nowMs);

        // Best-effort cleanup to prevent unbounded growth.
        if (map.size() > 5000) {
            long cutoff = nowMs - 10_000L;
            for (var it = map.entrySet().iterator(); it.hasNext(); ) {
                var e = it.next();
                if (e.getValue() == null || e.getValue() < cutoff) it.remove();
            }
        }
        return true;
    }

    private static void runAfterTicks(ServerLevel level, int delayTicks, Runnable r) {
        try {
            MinecraftServer srv = level.getServer();
            if (srv == null) return;
            int d = Math.max(1, delayTicks);
            srv.tell(new TickTask(srv.getTickCount() + d, r));
        } catch (Throwable ignored) {
        }
    }
    private static final class ContainerCtx {
        final String dim;
        final BlockPos pos;
        final String beforeSlots;

        ContainerCtx(String dim, BlockPos pos, String beforeSlots) {
            this.dim = dim;
            this.pos = pos;
            this.beforeSlots = beforeSlots;
        }
    }

    // player uuid -> last opened container context (best-effort)
    private static final Map<UUID, ContainerCtx> OPEN_CONTAINER = new ConcurrentHashMap<>();

    private static final class PendingBlockContainerOpen {
        final long ts;
        final String dim;
        final BlockPos pos;

        PendingBlockContainerOpen(long ts, String dim, BlockPos pos) {
            this.ts = ts;
            this.dim = dim;
            this.pos = pos;
        }
    }

    // player uuid -> staged block container open (confirmed later by PlayerContainerEvent.Open)
    private static final Map<UUID, PendingBlockContainerOpen> PENDING_BLOCK_CONTAINER_OPEN = new ConcurrentHashMap<>();

    private static final class EntityContainerCtx {
        final String dim;
        final UUID entityUuid;
        final String entityType;
        final String beforeInv;

        EntityContainerCtx(String dim, UUID entityUuid, String entityType, String beforeInv) {
            this.dim = dim;
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.beforeInv = beforeInv;
        }
    }

    // player uuid -> last opened ENTITY container context (best-effort)
    private static final Map<UUID, EntityContainerCtx> OPEN_ENTITY_CONTAINER = new ConcurrentHashMap<>();

    private static final class PendingEntityContainerOpen {
        final long ts;
        final String dim;
        final UUID entityUuid;
        final String entityType;
        final String beforeInv;

        PendingEntityContainerOpen(long ts, String dim, UUID entityUuid, String entityType, String beforeInv) {
            this.ts = ts;
            this.dim = dim;
            this.entityUuid = entityUuid;
            this.entityType = entityType;
            this.beforeInv = beforeInv;
        }
    }

    // player uuid -> staged entity container open (confirmed later by PlayerContainerEvent.Open)
    private static final Map<UUID, PendingEntityContainerOpen> PENDING_ENTITY_CONTAINER_OPEN = new ConcurrentHashMap<>();

    private static final class DropAgg {
        final UUID actorUuid;
        final String actorName;
        final String dim;
        final String itemKeySnbt; // normalized stack (count=1)
        int count;
        int x, y, z;
        long lastTs;

        DropAgg(UUID actorUuid, String actorName, String dim, String itemKeySnbt, int count, int x, int y, int z, long lastTs) {
            this.actorUuid = actorUuid;
            this.actorName = actorName;
            this.dim = dim;
            this.itemKeySnbt = itemKeySnbt;
            this.count = count;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastTs = lastTs;
        }
    }

    // key: playerUuid + ":" + normalizedStackSnbt
    private static final Map<String, DropAgg> DROP_AGG = new ConcurrentHashMap<>();


    private static final class PreDeathSnapshot {
        final long ts;
        final String dim;
        final int x, y, z;
        final String entityType;
        final java.util.UUID entityUuid;
        final String entityNbt; // snapshot while entity is still alive

        PreDeathSnapshot(long ts, String dim, int x, int y, int z, String entityType, java.util.UUID entityUuid, String entityNbt) {
            this.ts = ts;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.entityType = entityType;
            this.entityUuid = entityUuid;
            this.entityNbt = entityNbt;
        }
    }

    // entity uuid -> snapshot captured right before lethal damage
    private static final Map<java.util.UUID, PreDeathSnapshot> PRE_DEATH = new ConcurrentHashMap<>();


    @SubscribeEvent
    public void onBlockBreak(BlockEvent.BreakEvent event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logBlocks.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player p = event.getPlayer();
        if (p == null) return;

        // In inspect mode, prevent actual breaking (important for Creative where client can destroy instantly).
        if (InspectManager.isInspecting(p) && InspectManager.isHoldingTool(p)) {
            event.setCanceled(true);
            if (p instanceof ServerPlayer sp) {
                inspectAndSend(level, sp, event.getPos(), level.getBlockState(event.getPos()));
            }
            return;
        }

        ItemStack used0 = ItemStack.EMPTY;
        try { if (p instanceof ServerPlayer sp0) used0 = sp0.getMainHandItem(); } catch (Throwable ignored) {}
        try (var scope = CauseContext.push(p, CauseContext.Kind.BREAK_BLOCK, event.getPos(), used0)) {

        BlockPos pos = event.getPos();
        BlockState before = level.getBlockState(pos);
        BlockEntity be = level.getBlockEntity(pos);

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.BLOCK_BREAK;
        e.actorUuid = p.getUUID();
        e.actorName = p.getName().getString();
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.blockBefore = NbtSerde.writeBlockState(before);
        e.beBefore = NbtSerde.writeBlockEntity(level, be);
        e.blockAfter = NbtSerde.writeBlockState(level.getBlockState(pos)); // may still be before, but ok
        e.extra = "break " + BuiltInRegistries.BLOCK.getKey(before.getBlock()).toString();
        LoggerRuntime.storage(level).append(e);

        // remember for delayed attribution
        try {
            if (p instanceof ServerPlayer sp2) RecentPlayerActionTracker.note(level, sp2, pos, RecentPlayerActionTracker.ActionKind.BREAK_BLOCK, sp2.getMainHandItem());
        } catch (Throwable ignored) {}

        }
    }

    @SubscribeEvent
    public void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logBlocks.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity entity = event.getEntity();
        if (!(entity instanceof Player p)) return;

        // In inspect mode, block placement should not occur.
        if (InspectManager.isInspecting(p) && InspectManager.isHoldingTool(p)) {
            event.setCanceled(true);
            return;
        }

        ItemStack used0 = ItemStack.EMPTY;
        try { if (p instanceof ServerPlayer sp0) used0 = sp0.getMainHandItem(); } catch (Throwable ignored) {}
        try (var scope = CauseContext.push(p, CauseContext.Kind.PLACE_BLOCK, event.getPos(), used0)) {

        BlockPos pos = event.getPos();
        BlockState placed = event.getPlacedBlock();
        BlockState replaced = event.getBlockSnapshot().getState();
        BlockEntity beAfter = level.getBlockEntity(pos);

        // Best-effort old block-entity snapshot (BlockSnapshot API differs across NeoForge builds).
        String beBeforeSnbt = null;
        try {
            Object snap = event.getBlockSnapshot();
            if (snap != null) {
                var m = snap.getClass().getMethod("getTag");
                Object tag = m.invoke(snap);
                if (tag instanceof net.minecraft.nbt.CompoundTag ct) {
                    beBeforeSnbt = ct.toString();
                }
            }
        } catch (Throwable ignored) {
        }

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.BLOCK_PLACE;
        e.actorUuid = p.getUUID();
        e.actorName = p.getName().getString();
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.blockBefore = NbtSerde.writeBlockState(replaced);
        e.beBefore = beBeforeSnbt;
        e.blockAfter = NbtSerde.writeBlockState(placed);
        e.beAfter = NbtSerde.writeBlockEntity(level, beAfter);
        e.extra = "place " + BuiltInRegistries.BLOCK.getKey(placed.getBlock()) + " (was " + BuiltInRegistries.BLOCK.getKey(replaced.getBlock()) + ")";
        LoggerRuntime.storage(level).append(e);

        // remember for delayed attribution
        try {
            if (p instanceof ServerPlayer sp2) RecentPlayerActionTracker.note(level, sp2, pos, RecentPlayerActionTracker.ActionKind.PLACE_BLOCK, sp2.getMainHandItem());
        } catch (Throwable ignored) {}

        }
    }

    @SubscribeEvent
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player p = event.getEntity();
        if (p == null) return;

        if (InspectManager.isInspecting(p) && InspectManager.isHoldingTool(p)) {
            BlockPos pos = event.getPos();
            BlockState state = level.getBlockState(pos);
            if (p instanceof ServerPlayer sp) {
                inspectAndSend(level, sp, pos, state);
            }
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        ItemStack used0 = event.getItemStack() != null ? event.getItemStack() : ItemStack.EMPTY;
        try (var scope = CauseContext.push(sp, CauseContext.Kind.USE_ITEM, sp.blockPosition(), used0)) {
            // Some items spawn entities without going through RightClickBlock.
            try { RecentPlayerActionTracker.note(level, sp, sp.blockPosition(), RecentPlayerActionTracker.ActionKind.RIGHT_CLICK_ITEM, used0); } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Player p = event.getEntity();
        if (p == null) return;

        ItemStack used0 = event.getItemStack() != null ? event.getItemStack() : ItemStack.EMPTY;
        try (var scope = CauseContext.push(p, CauseContext.Kind.USE_BLOCK, event.getPos(), used0)) {

        // Remember recent interaction for best-effort attribution of entity spawns
        // that do not preserve the placer/actor (e.g. modded frames/decorations).
        try {
            if (p instanceof ServerPlayer sp) {
                BlockPos base = event.getPos();
                RecentPlayerActionTracker.note(level, sp, base, RecentPlayerActionTracker.ActionKind.RIGHT_CLICK_BLOCK, used0);
                try {
                    var face = event.getFace();
                        if (face != null) RecentPlayerActionTracker.note(level, sp, base.relative(face), RecentPlayerActionTracker.ActionKind.RIGHT_CLICK_BLOCK, used0);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}

        // Track Create device interactions for later attribution (Schematicannon/contraptions).
        try { CreateOwnershipTracker.onRightClickBlock(event); } catch (Throwable ignored) {}

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);

        // Inspect tool mode (CoreProtect-like): when enabled, right-click with the configured tool prints history
        // and does NOT trigger the normal interaction.
        if (InspectManager.isInspecting(p) && InspectManager.isHoldingTool(p)) {
            if (p instanceof ServerPlayer sp) {
                inspectAndSend(level, sp, pos, state);
            }
            event.setCanceled(true);
            return;
        }

        // 1) If this looks like a container that opens a menu, stage it and log "open".
        // Actual inventory snapshot must be taken from real storage at PlayerContainerEvent.Open/Close.
        if (LoggerConfig.VALUES.logContainers.get() && isInventoryLike(level, pos, state)) {
            String dim = level.dimension().location().toString();
            PENDING_BLOCK_CONTAINER_OPEN.put(p.getUUID(), new PendingBlockContainerOpen(System.currentTimeMillis(), dim, pos.immutable()));

            if (LoggerConfig.VALUES.logBlocks.get()) {
                LogEntry e = new LogEntry();
                e.ts = System.currentTimeMillis();
                e.dim = dim;
                e.type = ActionType.CONTAINER_OPEN;
                e.actorUuid = p.getUUID();
                e.actorName = p.getName().getString();
                e.x = pos.getX();
                e.y = pos.getY();
                e.z = pos.getZ();
                e.blockAfter = NbtSerde.writeBlockState(state);
                e.extra = "open " + BuiltInRegistries.BLOCK.getKey(state.getBlock());
                LoggerRuntime.storage(level).append(e);
            }
        }

        // 2) Generic interaction logging (fillable blocks, depot-like blocks, modded mechanics).
        //    We snapshot the blockstate and, if present, block-entity NBT BEFORE interaction and compare next tick.
        if (!LoggerConfig.VALUES.logBlocks.get()) return;
        if (!shouldTrackDelayedInteraction(level, pos, state)) return;
        final String dim = level.dimension().location().toString();
        final String beforeState = NbtSerde.writeBlockState(state);
        final BlockEntity be0 = level.getBlockEntity(pos);
        final String beforeBe = (LoggerConfig.VALUES.logContainers.get() && be0 != null) ? NbtSerde.writeBlockEntity(level, be0) : null;
        final ItemStack used = event.getItemStack() != null ? event.getItemStack().copy() : ItemStack.EMPTY;
        final String usedItemSnbt = (!used.isEmpty()) ? NbtSerde.writeItemStack(used, level.registryAccess()) : null;
        final UUID actorUuid = p.getUUID();
        final String actorName = p.getName().getString();

        final java.util.concurrent.atomic.AtomicBoolean logged = new java.util.concurrent.atomic.AtomicBoolean(false);

        // Some blocks mutate state/BE not immediately (modded mechanics). Check a short window of ticks.
        for (int t = 1, maxScanTicks = Math.max(1, LoggerConfig.VALUES.interactionScanTicks.get()); t <= maxScanTicks; t++) {
            int delay = t;
            runAfterTicks(level, delay, () -> {
                if (logged.get()) return;
                try {
                BlockState afterState0 = level.getBlockState(pos);
                String afterState = NbtSerde.writeBlockState(afterState0);
                BlockEntity be1 = level.getBlockEntity(pos);
                String afterBe = (LoggerConfig.VALUES.logContainers.get() && be1 != null) ? NbtSerde.writeBlockEntity(level, be1) : null;

                boolean stateChanged = beforeState != null && afterState != null && !afterState.equals(beforeState);
                boolean beChanged = beforeBe != null && afterBe != null && !afterBe.equals(beforeBe);

                if (!stateChanged && !beChanged) return;
                logged.set(true);

                // Human-friendly block interaction entry (covers cauldrons/composters/etc.)
                if (stateChanged) {
                    LogEntry ie = new LogEntry();
                    ie.ts = System.currentTimeMillis();
                    ie.dim = dim;
                    ie.type = ActionType.BLOCK_INTERACT;
                    ie.actorUuid = actorUuid;
                    ie.actorName = actorName;
                    ie.x = pos.getX();
                    ie.y = pos.getY();
                    ie.z = pos.getZ();
                    ie.blockBefore = beforeState;
                    ie.blockAfter = afterState;
                    if (beChanged && LoggerConfig.VALUES.storeVerboseBeSnapshotsInInteractLogs.get()) {
                        ie.beBefore = beforeBe;
                        ie.beAfter = afterBe;
                    }
                    ie.itemStackNbt = usedItemSnbt;
                    ie.extra = "interact " + BuiltInRegistries.BLOCK.getKey(afterState0.getBlock());
                    LoggerRuntime.storage(level).append(ie);
                }

                // Block-entity inventory / data changes without a GUI (Create Depot, modded blocks, etc.)
                if (beChanged) {
                    var diffs = InventoryDiffUtil.diff(beforeBe, afterBe, level.registryAccess());
                    if (diffs != null && !diffs.isEmpty()) {
                        for (var d : diffs) {
                            LogEntry de = new LogEntry();
                            de.ts = System.currentTimeMillis();
                            de.dim = dim;
                            de.type = (d.deltaCount() > 0) ? ActionType.CONTAINER_PUT : ActionType.CONTAINER_TAKE;
                            de.actorUuid = actorUuid;
                            de.actorName = actorName;
                            de.x = pos.getX();
                            de.y = pos.getY();
                            de.z = pos.getZ();
                            de.blockAfter = NbtSerde.writeBlockState(afterState0);
                            if (LoggerConfig.VALUES.storeVerboseBeSnapshotsInDeltaLogs.get()) {
                                de.beBefore = beforeBe;
                                de.beAfter = afterBe;
                            }
                            de.count = Math.abs(d.deltaCount());
                            try {
                                ItemStack st = d.representative().copy();
                                st.setCount(Math.max(1, Math.abs(d.deltaCount())));
                                de.itemStackNbt = NbtSerde.writeItemStack(st, level.registryAccess());
                            } catch (Throwable ignored) {}
                            LoggerRuntime.storage(level).append(de);
                        }
                    }

                    // Keep snapshot entry for deterministic rollback.
                    LogEntry se = new LogEntry();
                    se.ts = System.currentTimeMillis();
                    se.dim = dim;
                    se.type = ActionType.BLOCK_ENTITY_NBT_CHANGE;
                    se.actorUuid = actorUuid;
                    se.actorName = actorName;
                    se.x = pos.getX();
                    se.y = pos.getY();
                    se.z = pos.getZ();
                    se.blockAfter = NbtSerde.writeBlockState(afterState0);
                    se.beBefore = beforeBe;
                    se.beAfter = afterBe;
                    se.extra = "container change " + BuiltInRegistries.BLOCK.getKey(afterState0.getBlock());
                    LoggerRuntime.storage(level).append(se);
                }
                } catch (Throwable ignored) {
                }
            });
        }
        }
    }


    @SubscribeEvent
    public void onAttackEntity(AttackEntityEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;
        Entity target = event.getTarget();
        if (target == null) return;
        if (target instanceof Player) return;
        try (var scope = CauseContext.push(sp, CauseContext.Kind.ATTACK_ENTITY, target.blockPosition(), sp.getMainHandItem())) {
            ActorTracker.note(target.getUUID(), sp.getUUID(), sp.getName().getString());
            try { RecentPlayerActionTracker.note(level, sp, target.blockPosition(), RecentPlayerActionTracker.ActionKind.ATTACK_ENTITY, sp.getMainHandItem()); } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;
        Entity target = event.getTarget();
        if (target == null) return;
        if (target instanceof Player) return;

        try (var scope = CauseContext.push(sp, CauseContext.Kind.INTERACT_ENTITY, target.blockPosition(), sp.getMainHandItem())) {

            // Remember actor for later removal attribution.
            ActorTracker.note(target.getUUID(), sp.getUUID(), sp.getName().getString());

            // Also remember the interaction location to attribute follow-up spawns.
            try { RecentPlayerActionTracker.note(level, sp, target.blockPosition(), RecentPlayerActionTracker.ActionKind.INTERACT_ENTITY, sp.getMainHandItem()); } catch (Throwable ignored) {}

        // Entity container (planes, modded vehicles, etc.)
        // For Immersive Aircraft / Man of Many Planes inventory opens only via:
        //  - Shift + RMB when outside
        //  - Press E when riding (inside)
        // So we DON'T log on every RMB. We stage a pending open when player is sneaking;
        // the actual open is confirmed by PlayerContainerEvent.Open.
            if (!LoggerConfig.VALUES.logContainers.get()) return;
            if (!(target instanceof Container cont)) return;
            if (!sp.isShiftKeyDown()) return;

            String dim = level.dimension().location().toString();
            String beforeInv = EntityContainerSerde.write(cont, level.registryAccess());
            if (beforeInv == null) return;

            String entityType = EntityType.getKey(target.getType()).toString();
            PENDING_ENTITY_CONTAINER_OPEN.put(sp.getUUID(), new PendingEntityContainerOpen(System.currentTimeMillis(), dim, target.getUUID(), entityType, beforeInv));
        }
    }

    @SubscribeEvent
    public void onContainerOpen(PlayerContainerEvent.Open event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logContainers.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        String dim = level.dimension().location().toString();

        // 0) Block container open confirmed by menu open.
        try {
            PendingBlockContainerOpen pending = PENDING_BLOCK_CONTAINER_OPEN.remove(sp.getUUID());
            if (pending != null && pending.dim.equals(dim) && (System.currentTimeMillis() - pending.ts) <= 1200L) {
                String beforeSlots = ContainerSlotSnapshot.snapshot(level, pending.pos);
                if (beforeSlots != null) {
                    OPEN_CONTAINER.put(sp.getUUID(), new ContainerCtx(dim, pending.pos, beforeSlots));
                }
            }
        } catch (Throwable ignored) {}

        // 1) When player presses E while riding a vehicle with inventory.
        try {
            Entity vehicle = sp.getVehicle();
            if (vehicle instanceof Container cont && !(vehicle instanceof Player)) {
                String beforeInv = EntityContainerSerde.write(cont, level.registryAccess());
                if (beforeInv != null) {
                    String entityType = EntityType.getKey(vehicle.getType()).toString();
                    logEntityContainerOpen(level, sp, vehicle, dim, entityType, beforeInv);
                    OPEN_ENTITY_CONTAINER.put(sp.getUUID(), new EntityContainerCtx(dim, vehicle.getUUID(), entityType, beforeInv));
                    return;
                }
            }
        } catch (Throwable ignored) {}

        // 2) When player shift-RMB outside: we staged it in EntityInteract.
        try {
            PendingEntityContainerOpen pending = PENDING_ENTITY_CONTAINER_OPEN.remove(sp.getUUID());
            if (pending != null && pending.dim.equals(dim) && (System.currentTimeMillis() - pending.ts) <= 1200L) {
                Entity ent = level.getEntity(pending.entityUuid);
                if (ent instanceof Container) {
                    logEntityContainerOpen(level, sp, ent, dim, pending.entityType, pending.beforeInv);
                    OPEN_ENTITY_CONTAINER.put(sp.getUUID(), new EntityContainerCtx(dim, pending.entityUuid, pending.entityType, pending.beforeInv));
                }
            }
        } catch (Throwable ignored) {}
    }

    private static void logEntityContainerOpen(ServerLevel level, ServerPlayer sp, Entity target, String dim, String entityType, String beforeInv) {
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = dim;
        e.type = ActionType.ENTITY_CONTAINER_OPEN;
        e.actorUuid = sp.getUUID();
        e.actorName = sp.getName().getString();
        e.x = target.blockPosition().getX();
        e.y = target.blockPosition().getY();
        e.z = target.blockPosition().getZ();
        e.entityType = entityType;
        e.entityUuid = target.getUUID();
        e.extra = "open entity container " + entityType;
        // Store a snapshot too (so rollback tools can be deterministic if you add them later)
        e.entityNbt = NbtSerde.writeEntity(level, target);
        // beforeInv is kept in OPEN_ENTITY_CONTAINER ctx
        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onEntityMount(EntityMountEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntityMounting() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        Entity ridden = event.getEntityBeingMounted();
        if (ridden == null) return;
        if (ridden instanceof Player) return;

        ActorTracker.note(ridden.getUUID(), sp.getUUID(), sp.getName().getString());

        // Log mount / dismount for vehicles (includes planes).
        if (!LoggerConfig.VALUES.logEntities.get()) return;

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        boolean isPlane = false;
        try { isPlane = AirplanesCompatHooks.isPlaneEntity(ridden); } catch (Throwable ignored) {}

        // AirPlanesLogger parity: plane "mount" is a dedicated type.
        if (event.isMounting() && isPlane) {
            e.type = ActionType.PLANE_MOUNT;
        } else {
            e.type = event.isMounting() ? ActionType.ENTITY_MOUNT : ActionType.ENTITY_DISMOUNT;
        }
        e.actorUuid = sp.getUUID();
        e.actorName = sp.getName().getString();
        e.x = ridden.blockPosition().getX();
        e.y = ridden.blockPosition().getY();
        e.z = ridden.blockPosition().getZ();
        e.entityType = EntityType.getKey(ridden.getType()).toString();
        e.entityUuid = ridden.getUUID();
        e.entityNbt = NbtSerde.writeEntity(level, ridden);
        if (event.isMounting() && isPlane) {
            e.extra = "plane mount " + e.entityType;
        } else {
            e.extra = (event.isMounting() ? "mount " : "dismount ") + e.entityType;
        }
        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onLeftClickBlockInspect(PlayerInteractEvent.LeftClickBlock event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;
        if (!InspectManager.isInspecting(sp)) return;
        if (!InspectManager.isHoldingTool(sp)) return;

        BlockPos pos = event.getPos();
        BlockState state = level.getBlockState(pos);
        inspectAndSend(level, sp, pos, state);
        event.setCanceled(true);
    }


    @SubscribeEvent
    public void onContainerClose(PlayerContainerEvent.Close event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logContainers.get()) return;
        if (!(event.getEntity() instanceof ServerPlayer sp)) return;
        Level lvl = sp.level();
        if (!(lvl instanceof ServerLevel level)) return;


        // 0) Entity container close (planes / vehicles). Emit put/take deltas.
        try {
            EntityContainerCtx ectx = OPEN_ENTITY_CONTAINER.remove(sp.getUUID());
            if (ectx != null && ectx.dim.equals(level.dimension().location().toString())) {
                Entity ent = level.getEntity(ectx.entityUuid);
                if (ent instanceof Container cont) {
                    String afterInv = EntityContainerSerde.write(cont, level.registryAccess());
                    if (afterInv != null && !afterInv.equals(ectx.beforeInv)) {
                        var diffs = InventoryDiffUtil.diff(ectx.beforeInv, afterInv, level.registryAccess());
                        if (diffs != null && !diffs.isEmpty()) {
                            for (var d : diffs) {
                                LogEntry de = new LogEntry();
                                de.ts = System.currentTimeMillis();
                                de.dim = ectx.dim;
                                de.type = (d.deltaCount() > 0) ? ActionType.CONTAINER_PUT : ActionType.CONTAINER_TAKE;
                                de.actorUuid = sp.getUUID();
                                de.actorName = sp.getName().getString();
                                de.x = ent.blockPosition().getX();
                                de.y = ent.blockPosition().getY();
                                de.z = ent.blockPosition().getZ();
                                de.entityType = ectx.entityType;
                                de.entityUuid = ectx.entityUuid;
                                de.count = Math.abs(d.deltaCount());
                                try {
                                    ItemStack st = d.representative().copy();
                                    st.setCount(Math.max(1, Math.abs(d.deltaCount())));
                                    de.itemStackNbt = NbtSerde.writeItemStack(st, level.registryAccess());
                                } catch (Throwable ignored) {}
                                de.extra = "entity container " + ectx.entityType;
                                LoggerRuntime.storage(level).append(de);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        ContainerCtx ctx = OPEN_CONTAINER.remove(sp.getUUID());
        if (ctx == null) return;
        if (!ctx.dim.equals(level.dimension().location().toString())) return;

        // Strict slot snapshot diff from real storage.
        String afterSlots = ContainerSlotSnapshot.snapshot(level, ctx.pos);
        if (afterSlots == null) return;
        if (afterSlots.equals(ctx.beforeSlots)) return;

        // Emit human-friendly aggregated put/take events.
        try {
            var agg = ContainerSlotDiffUtil.diffAggregated(ctx.beforeSlots, afterSlots, level.registryAccess());
            if (agg != null && !agg.isEmpty()) {
                for (var ent : agg.entrySet()) {
                    int delta = ent.getValue();
                    if (delta == 0) continue;
                    LogEntry de = new LogEntry();
                    de.ts = System.currentTimeMillis();
                    de.dim = ctx.dim;
                    de.type = (delta > 0) ? ActionType.CONTAINER_PUT : ActionType.CONTAINER_TAKE;
                    de.actorUuid = sp.getUUID();
                    de.actorName = sp.getName().getString();
                    de.x = ctx.pos.getX();
                    de.y = ctx.pos.getY();
                    de.z = ctx.pos.getZ();
                    de.blockAfter = NbtSerde.writeBlockState(level.getBlockState(ctx.pos));
                    de.count = Math.abs(delta);
                    // IMPORTANT: keep the normalized stack SNBT (Count=1) as the item key,
                    // and store the real amount in "count". This avoids DB truncation and
                    // guarantees we can always decode item id for UI/details.
                    de.itemStackNbt = ent.getKey();
                    de.extra = "container delta";
                    LoggerRuntime.storage(level).append(de);
                }
            }
        } catch (Throwable ignored) {}

        // Always keep a deterministic snapshot entry for rollback tools.
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = ctx.dim;
        e.type = ActionType.BLOCK_ENTITY_NBT_CHANGE;
        e.actorUuid = sp.getUUID();
        e.actorName = sp.getName().getString();
        e.x = ctx.pos.getX();
        e.y = ctx.pos.getY();
        e.z = ctx.pos.getZ();
        e.blockAfter = NbtSerde.writeBlockState(level.getBlockState(ctx.pos));
        e.containerSlotsBefore = ctx.beforeSlots;
        e.containerSlotsAfter = afterSlots;
        e.extra = "container change " + BuiltInRegistries.BLOCK.getKey(level.getBlockState(ctx.pos).getBlock());
        LoggerRuntime.storage(level).append(e);
    }

    
    /**
     * NeoForge 21.1.x делает LivingDamageEvent абстрактным. Подписываться нужно на конкретную фазу.
     * Используем Pre, чтобы поймать значение урона до финализации смерти.
     */
    @SubscribeEvent
    public void onEntityHurtPreDeath(LivingDamageEvent.Pre event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getEntity() instanceof Player) return; // игроков отдельно

        // Сохраняем снимок ДО применения смертельного урона, чтобы откат возвращал сущность "живой".
        try {
            float hp = event.getEntity().getHealth();
            float dmg = getDamageAmountCompat(event);
            if (hp > 0.0f && (hp - dmg) <= 0.0f) {
                String dim = level.dimension().location().toString();
                String nbt = NbtSerde.writeEntity(level, event.getEntity());
                if (nbt != null) {
                    // Snapshot meta helps later if we want to respawn at the same spot/type
                    var ent = event.getEntity();
                    PRE_DEATH.put(ent.getUUID(), new PreDeathSnapshot(
                            System.currentTimeMillis(),
                            dim,
                            ent.blockPosition().getX(), ent.blockPosition().getY(), ent.blockPosition().getZ(),
                            EntityType.getKey(ent.getType()).toString(),
                            ent.getUUID(),
                            nbt
                    ));
                }
            }
        } catch (Throwable ignored) {}
    }

    /**
     * NeoForge events have been renamed/shuffled across 21.1.x.
     * We keep this method reflective so the project compiles on 21.1.215+ without hard dependency
     * on a particular accessor name.
     */
    private static float getDamageAmountCompat(LivingDamageEvent event) {
        // Try common accessor names.
        String[] candidates = new String[]{"getAmount", "getNewDamage", "getDamage", "getDamageAmount", "getFinalDamage"};
        for (String name : candidates) {
            try {
                Method m = event.getClass().getMethod(name);
                Object v = m.invoke(event);
                if (v instanceof Number n) {
                    return n.floatValue();
                }
            } catch (Throwable ignored) {
            }
        }

        // Last resort: if the API exposes a damage container, try to extract from it.
        try {
            Method m = event.getClass().getMethod("getDamageContainer");
            Object dc = m.invoke(event);
            if (dc != null) {
                for (String name : new String[]{"getNewDamage", "getDamage", "getAmount", "getFinalDamage"}) {
                    try {
                        Method m2 = dc.getClass().getMethod(name);
                        Object v = m2.invoke(dc);
                        if (v instanceof Number n) return n.floatValue();
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {
        }

        return 0.0f;
    }


    @SubscribeEvent
    public void onEntityDeath(LivingDeathEvent event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        Entity victim = event.getEntity();

        Entity killer = event.getSource().getEntity();
        UUID actorUuid = null;
        String actorName = null;
        if (killer instanceof Player p) {
            actorUuid = p.getUUID();
            actorName = p.getName().getString();
        }

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = (victim instanceof Player) ? ActionType.PLAYER_DEATH : ActionType.ENTITY_DEATH;
        e.actorUuid = actorUuid;
        e.actorName = actorName;
        e.x = victim.blockPosition().getX();
        e.y = victim.blockPosition().getY();
        e.z = victim.blockPosition().getZ();
        e.entityType = EntityType.getKey(victim.getType()).toString();
        e.entityUuid = victim.getUUID();
        // В идеале откатываем сущность к состоянию ДО смертельного урона.
        String preNbt = null;
        if (!(victim instanceof Player)) {
            PreDeathSnapshot snap = PRE_DEATH.remove(victim.getUUID());
            if (snap != null && snap.entityNbt != null && snap.dim != null && snap.dim.equals(e.dim) && (System.currentTimeMillis() - snap.ts) <= 5000L) {
                preNbt = snap.entityNbt;
            }
        }
        e.entityNbt = (preNbt != null) ? preNbt : NbtSerde.writeEntity(level, victim);
        if (victim instanceof Player vp) {
            e.extra = "victim " + vp.getName().getString();
        } else {
            e.extra = "kill " + e.entityType;
        }
        LoggerRuntime.storage(level).append(e);
    }

    
    
    private static void recordPlayerDrop(ServerLevel level, ServerPlayer sp, int x, int y, int z, ItemStack st, long now) {
        if (level == null || sp == null || st == null || st.isEmpty()) return;
        try {
            ItemStack norm = st.copy();
            norm.setCount(1);
            String itemKey = NbtSerde.writeItemStack(norm, level.registryAccess());
            if (itemKey == null) return;

            String key = sp.getUUID() + ":" + itemKey;
            DropAgg agg = DROP_AGG.get(key);
            if (agg == null) {
                agg = new DropAgg(sp.getUUID(), sp.getName().getString(), level.dimension().location().toString(), itemKey, st.getCount(), x, y, z, now);
                DROP_AGG.put(key, agg);
            } else {
                agg.count += st.getCount();
                agg.x = x;
                agg.y = y;
                agg.z = z;
                agg.lastTs = now;
            }

            // Flush once the player stops spamming Q for a short time.
            runAfterTicks(level, 5, () -> flushDropIfIdle(level, key, 250L));
        } catch (Throwable ignored) {}
    }

    private static void flushDropIfIdle(ServerLevel level, String key, long idleMs) {
        if (level == null || key == null) return;
        try {
            DropAgg agg = DROP_AGG.get(key);
            if (agg == null) return;
            long now = System.currentTimeMillis();
            long dt = (now - agg.lastTs);
            if (dt < idleMs) {
                // still active; reschedule until the player stops dropping for idleMs
                int more = (int) Math.ceil((idleMs - dt) / 50.0D);
                runAfterTicks(level, Math.max(1, more), () -> flushDropIfIdle(level, key, idleMs));
                return;
            }

            DROP_AGG.remove(key);

            LogEntry e = new LogEntry();
            e.ts = now;
            e.dim = agg.dim;
            e.type = ActionType.ITEM_DROP;
            e.actorUuid = agg.actorUuid;
            e.actorName = agg.actorName;
            e.x = agg.x;
            e.y = agg.y;
            e.z = agg.z;
            // Recreate a representative stack with total count.
            ItemStack rep = NbtSerde.readItemStack(agg.itemKeySnbt, level.registryAccess());
            if (rep != null && !rep.isEmpty()) {
                rep.setCount(Math.max(1, agg.count));
                e.itemStackNbt = NbtSerde.writeItemStack(rep, level.registryAccess());
            } else {
                e.itemStackNbt = agg.itemKeySnbt;
            }
            e.count = Math.max(1, agg.count);
            e.extra = "drop";
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

@SubscribeEvent
    public void onItemToss(ItemTossEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        Player pl = event.getPlayer();
        if (!(pl instanceof ServerPlayer sp)) return;
        if (!(sp.level() instanceof ServerLevel level)) return;

        ItemEntity ie = event.getEntity();
        if (ie == null) return;

        long now = System.currentTimeMillis();
        String dedupKey = sp.getUUID() + ":" + ie.getUUID();
        if (!shouldLogRecent(RECENT_DROPS, dedupKey, now, 250L)) return;

        ItemStack st = ie.getItem();
        if (st == null || st.isEmpty()) return;

        // Aggregate Q-spam into a single logical drop entry.
        recordPlayerDrop(level, sp, ie.blockPosition().getX(), ie.blockPosition().getY(), ie.blockPosition().getZ(), st, now);

    }

@SubscribeEvent
    public void onEntitySpawn(EntityJoinLevelEvent event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        Entity ent = event.getEntity();
        if (ent instanceof Player) return;

        // Tag Create-related minecarts/entities with owner info (best-effort).
        try { CreateOwnershipTracker.onEntityJoin(event); } catch (Throwable ignored) {}

        // 1) ItemEntity spawn: only player drops (otherwise too noisy).
        if (ent instanceof ItemEntity itemEnt) {
            java.util.UUID throwerUuid = getItemEntityThrowerUuid(itemEnt);
            if (throwerUuid == null) return;

            long now = System.currentTimeMillis();
            String dedupKey = throwerUuid + ":" + itemEnt.getUUID();
            if (!shouldLogRecent(RECENT_DROPS, dedupKey, now, 250L)) return;

            Player thrower = level.getPlayerByUUID(throwerUuid);
            ItemStack st = itemEnt.getItem();

            // If the thrower is online and it's a player-caused drop, aggregate spammy Q-drops.
            if (thrower instanceof ServerPlayer sp2) {
                recordPlayerDrop(level, sp2, itemEnt.blockPosition().getX(), itemEnt.blockPosition().getY(), itemEnt.blockPosition().getZ(), st, now);
                return;
            }

            String throwerName = (thrower != null) ? thrower.getName().getString() : null;

            LogEntry e = new LogEntry();
            e.ts = now;
            e.dim = level.dimension().location().toString();
            e.type = ActionType.ITEM_DROP;
            e.actorUuid = throwerUuid;
            e.actorName = throwerName;
            e.x = itemEnt.blockPosition().getX();
            e.y = itemEnt.blockPosition().getY();
            e.z = itemEnt.blockPosition().getZ();
            e.itemStackNbt = NbtSerde.writeItemStack(st, level.registryAccess());
            e.count = st.getCount();
            e.extra = "drop " + BuiltInRegistries.ITEM.getKey(st.getItem());
            LoggerRuntime.storage(level).append(e);
            return;

        }

        // 2) Non-living / misc entities (vehicles, plane mods, etc.).
        if (ent instanceof Projectile || ent instanceof ExperienceOrb) return;
        if (ent.getType().getCategory() != MobCategory.MISC) return;

        boolean isPlane = false;
        try { isPlane = AirplanesCompatHooks.isPlaneEntity(ent); } catch (Throwable ignored) {}

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        // AirPlanesLogger parity: plane placement is its own type.
        e.type = isPlane ? ActionType.PLANE_PLACE : ActionType.ENTITY_SPAWN;
        e.actorUuid = null;
        e.actorName = null;
        e.x = ent.blockPosition().getX();
        e.y = ent.blockPosition().getY();
        e.z = ent.blockPosition().getZ();
        e.entityType = EntityType.getKey(ent.getType()).toString();
        e.entityUuid = ent.getUUID();
        e.entityNbt = NbtSerde.writeEntity(level, ent);

        // Best-effort attribution for entity placements that don't preserve a placer.
        // (frames, decorative entities, many modded MISC spawns).
        try {
            RecentPlayerActionTracker.ActionRef rr = RecentPlayerActionTracker.resolve(level, ent.blockPosition(), 2, 5000L);
            if (rr != null && rr.actorUuid() != null) {
                e.actorUuid = rr.actorUuid();
                e.actorName = rr.actorName();
                try { ActorTracker.note(ent.getUUID(), rr.actorUuid(), rr.actorName()); } catch (Throwable ignored2) {}
                try {
                    ent.getPersistentData().putString("avilixlogger_owner_uuid", rr.actorUuid().toString());
                    if (rr.actorName() != null && !rr.actorName().isBlank()) ent.getPersistentData().putString("avilixlogger_owner_name", rr.actorName());
                    ent.getPersistentData().putInt("avilixlogger_owner_conf", (int) Math.round(rr.confidence() * 100.0));
                    ent.getPersistentData().putString("avilixlogger_owner_src", rr.source());
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        ActorTracker.ActorRef ar = resolveActorForEntity(level, ent, e.entityNbt);
        if (ar != null) {
            e.actorUuid = ar.actorUuid();
            e.actorName = ar.actorName();
        }

        if (isPlane) {
            String ownerName = null;
            String ownerUuid = null;
            String planeName = null;
            String customName = null;
            try {
                var tag = NbtSerde.fromSnbt(e.entityNbt);
                ownerName = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerNameFromTag(tag);
                ownerUuid = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerUuidFromTag(tag);

                // Best-effort plane name for display/search.
                // - planeName: the current in-game display name (localized if no custom name)
                // - customName: if present (entity CustomName JSON)
                try {
                    planeName = ent.getDisplayName() != null ? ent.getDisplayName().getString() : null;
                } catch (Throwable ignored2) {}
                try {
                    if (tag != null && tag.contains("CustomName")) {
                        customName = tag.getString("CustomName");
                    }
                } catch (Throwable ignored2) {}
            } catch (Throwable ignored) {}

            String safePlaneId = (e.entityType == null ? "" : e.entityType);
            String safePlaneName = (planeName == null ? "" : planeName).replace("\\", "\\\\").replace("\"", "\\\"");
            String safeCustom = (customName == null ? "" : customName).replace("\\", "\\\\").replace("\"", "\\\"");
            String safeOwnerName = (ownerName == null ? "" : ownerName).replace("\\", "\\\\").replace("\"", "\\\"");
            String safeOwnerUuid = (ownerUuid == null ? "" : ownerUuid).replace("\\", "\\\\").replace("\"", "\\\"");
            e.extra = "{\"kind\":\"plane_place\",\"planeId\":\"" + safePlaneId + "\",\"planeName\":\"" + safePlaneName + "\",\"customName\":\"" + safeCustom + "\",\"ownerName\":\"" + safeOwnerName + "\",\"ownerUuid\":\"" + safeOwnerUuid + "\"}";
        } else {
            e.extra = "spawn " + e.entityType;
        }
        LoggerRuntime.storage(level).append(e);
    }
    
    @SubscribeEvent
    public void onItemPickup(ItemEntityPickupEvent.Pre event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getPlayer().level() instanceof ServerLevel level)) return;

        // Some mods/edge-cases trigger multiple pickup pre-events for the same item.
        // Also avoid counting immediate re-pickup of a freshly dropped item as a "pickup".
        Player pl = event.getPlayer();
        if (pl == null) return;

        // В Post-событии ItemEntity уже может быть удалён/обнулён, поэтому берём стек ДО подбора.
        ItemEntity ie = event.getItemEntity();
        if (ie == null) return;

        // 1) Dedup within a short window.
        long now = System.currentTimeMillis();
        String dedupKey = pl.getUUID() + ":" + ie.getUUID();
        if (!shouldLogRecent(RECENT_PICKUPS, dedupKey, now, 250L)) return;

        // 2) Ignore events while pickup delay is active (often happens around toss/spawn).
        if (getPickupDelayCompat(ie) > 0) return;

        // 3) Ignore instant pickup of own freshly thrown item (toss can trigger pickup hooks).
        java.util.UUID thrower = getItemEntityThrowerUuid(ie);
        if (thrower != null && thrower.equals(pl.getUUID())) {
            int ageTicks = getEntityAgeTicksCompat(ie);
            if (ageTicks >= 0 && ageTicks < 20) return;
        }

        ItemStack st = ie.getItem();
        if (st == null || st.isEmpty()) return;
        ItemStack snap = st.copy();

        // Planes: log as a dedicated action type (AirPlanesLogger parity).
        boolean isPlaneItem = false;
        try { isPlaneItem = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.isPlaneItem(snap); } catch (Throwable ignored) {}
        if (isPlaneItem) {
            LogEntry pe = new LogEntry();
            pe.ts = System.currentTimeMillis();
            pe.dim = level.dimension().location().toString();
            pe.type = ActionType.PLANE_PICKUP;
            pe.actorUuid = pl.getUUID();
            pe.actorName = pl.getName().getString();
            pe.x = ie.blockPosition().getX();
            pe.y = ie.blockPosition().getY();
            pe.z = ie.blockPosition().getZ();
            pe.itemStackNbt = NbtSerde.writeItemStack(snap, level.registryAccess());
            pe.count = snap.getCount();

            // Put owner + plane info into extra for easy filtering.
            try {
                var ot = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.getOwnerTag(snap);
                String on = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerNameFromTag(ot);
                String ou = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerUuidFromTag(ot);
                String planeName = null;
                String customName = null;
                try {
                    planeName = snap.getHoverName() != null ? snap.getHoverName().getString() : null;
                    if (snap.has(DataComponents.CUSTOM_NAME)) customName = snap.getHoverName().getString();
                } catch (Throwable ignored2) {}
                String planeId = "";
                try {
                    planeId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(snap.getItem()).toString();
                } catch (Throwable ignored2) {}

                String safePlaneId = (planeId == null ? "" : planeId).replace("\\", "\\\\").replace("\"", "\\\"");
                String safePlaneName = (planeName == null ? "" : planeName).replace("\\", "\\\\").replace("\"", "\\\"");
                String safeCustom = (customName == null ? "" : customName).replace("\\", "\\\\").replace("\"", "\\\"");
                String safeName = (on == null ? "" : on).replace("\\", "\\\\").replace("\"", "\\\"");
                String safeUuid = (ou == null ? "" : ou).replace("\\", "\\\\").replace("\"", "\\\"");
                pe.extra = "{\"kind\":\"plane_pickup\",\"planeId\":\"" + safePlaneId + "\",\"planeName\":\"" + safePlaneName + "\",\"customName\":\"" + safeCustom + "\",\"ownerName\":\"" + safeName + "\",\"ownerUuid\":\"" + safeUuid + "\"}";
            } catch (Throwable ignored) {
                pe.extra = "{\"kind\":\"plane_pickup\"}";
            }

            LoggerRuntime.storage(level).append(pe);
            return; // avoid duplicate ITEM_PICKUP
        }
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.ITEM_PICKUP;
        e.actorUuid = pl.getUUID();
        e.actorName = pl.getName().getString();
        e.x = ie.blockPosition().getX();
        e.y = ie.blockPosition().getY();
        e.z = ie.blockPosition().getZ();
        e.itemStackNbt = NbtSerde.writeItemStack(snap, level.registryAccess());
        e.count = snap.getCount();
        e.extra = "pickup " + BuiltInRegistries.ITEM.getKey(snap.getItem()) + " x" + snap.getCount();
        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onItemCrafted(PlayerEvent.ItemCraftedEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;
        if (event.getEntity() == null) return;

        ItemStack crafted = event.getCrafting();
        if (crafted == null || crafted.isEmpty()) return;

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.ITEM_CRAFT;
        e.actorUuid = event.getEntity().getUUID();
        e.actorName = event.getEntity().getName().getString();
        e.x = event.getEntity().blockPosition().getX();
        e.y = event.getEntity().blockPosition().getY();
        e.z = event.getEntity().blockPosition().getZ();
        e.itemStackNbt = NbtSerde.writeItemStack(crafted, level.registryAccess());
        e.count = crafted.getCount();
        e.extra = "craft " + BuiltInRegistries.ITEM.getKey(crafted.getItem()) + " x" + crafted.getCount();

        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onItemSmelted(PlayerEvent.ItemSmeltedEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        ItemStack smelted = event.getSmelting();
        if (smelted == null || smelted.isEmpty()) return;

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.ITEM_SMELT;
        e.actorUuid = event.getEntity().getUUID();
        e.actorName = event.getEntity().getName().getString();
        e.x = event.getEntity().blockPosition().getX();
        e.y = event.getEntity().blockPosition().getY();
        e.z = event.getEntity().blockPosition().getZ();
        e.itemStackNbt = NbtSerde.writeItemStack(smelted, level.registryAccess());
        e.count = smelted.getCount();
        e.extra = "smelt " + BuiltInRegistries.ITEM.getKey(smelted.getItem()) + " x" + smelted.getCount();
        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.PLAYER_JOIN;
        e.actorUuid = event.getEntity().getUUID();
        e.actorName = event.getEntity().getName().getString();
        e.x = event.getEntity().blockPosition().getX();
        e.y = event.getEntity().blockPosition().getY();
        e.z = event.getEntity().blockPosition().getZ();
        e.extra = "join";
        LoggerRuntime.storage(level).append(e);
    }

    @SubscribeEvent
    public void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!LoggerConfig.VALUES.enabled.get()) return;
        if (!(event.getEntity().level() instanceof ServerLevel level)) return;

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = ActionType.PLAYER_LEAVE;
        e.actorUuid = event.getEntity().getUUID();
        e.actorName = event.getEntity().getName().getString();
        e.x = event.getEntity().blockPosition().getX();
        e.y = event.getEntity().blockPosition().getY();
        e.z = event.getEntity().blockPosition().getZ();
        e.extra = "leave";
        LoggerRuntime.storage(level).append(e);
    }

    private static boolean isInventoryLike(ServerLevel level, BlockPos pos, BlockState state) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof Container) return true;
        return state.getMenuProvider(level, pos) != null;
    }

    private static boolean shouldTrackDelayedInteraction(ServerLevel level, BlockPos pos, BlockState state) {
        if (state == null) return false;
        try {
            if (isInventoryLike(level, pos, state)) return true;
        } catch (Throwable ignored) {}
        try {
            if (level.getBlockEntity(pos) != null) return true;
        } catch (Throwable ignored) {}
        try {
            return state.hasAnalogOutputSignal()
                    || state.hasBlockEntity()
                    || !state.getFluidState().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static BlockPos otherHalfChestPos(BlockPos pos, BlockState state) {
        if (!(state.getBlock() instanceof ChestBlock)) return null;
        if (!state.hasProperty(ChestBlock.TYPE) || !state.hasProperty(ChestBlock.FACING)) return null;
        ChestType type = state.getValue(ChestBlock.TYPE);
        if (type == ChestType.SINGLE) return null;
        Direction facing = state.getValue(ChestBlock.FACING);
        // In vanilla: the second half is offset by a perpendicular direction depending on left/right.
        return pos.relative(type == ChestType.LEFT ? facing.getClockWise() : facing.getCounterClockWise());
    }

    private static void inspectAndSend(ServerLevel level, ServerPlayer player, BlockPos pos, BlockState state) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = System.currentTimeMillis() - 7L * 24L * 60L * 60_000L; // last 7 days

        // Double chest: instead of merging two queries, query the minimal AABB that covers both halves.
        BlockPos other = otherHalfChestPos(pos, state);
        if (other != null) {
            int minX = Math.min(pos.getX(), other.getX());
            int maxX = Math.max(pos.getX(), other.getX());
            int minY = Math.min(pos.getY(), other.getY());
            int maxY = Math.max(pos.getY(), other.getY());
            int minZ = Math.min(pos.getZ(), other.getZ());
            int maxZ = Math.max(pos.getZ(), other.getZ());
            q.minPos = new BlockPos(minX, minY, minZ);
            q.maxPos = new BlockPos(maxX, maxY, maxZ);
        } else {
            q.exactPos = pos;
        }

        String blockId = String.valueOf(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
        String title = "Inspect " + blockId + " @ " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
        LastQueryManager.State st = LastQueryManager.set(player, q, title);
        ChatLogPager.renderAndSend(level, player, st);
    }

    private static java.util.UUID getItemEntityThrowerUuid(ItemEntity ent) {
        try {
            var m = ent.getClass().getMethod("getThrower");
            Object r = m.invoke(ent);
            if (r instanceof java.util.UUID u) return u;
        } catch (Throwable ignored) {
        }
        try {
            var m = ent.getClass().getMethod("getOwner");
            Object r = m.invoke(ent);
            if (r instanceof java.util.UUID u) return u;
        } catch (Throwable ignored) {
        }
        return null;
    }


    private static ActorTracker.ActorRef resolveActorForEntity(ServerLevel level, Entity ent, String entitySnbt) {
        if (ent == null) return null;

        // A) Precise cause stack (synchronous player action).
        try {
            CauseContext.Cause c = CauseContext.peek();
            if (c != null && c.actorUuid() != null) {
                return new ActorTracker.ActorRef(System.currentTimeMillis(), c.actorUuid(), c.actorName());
            }
        } catch (Throwable ignored) {}

        // 0) If the entity was previously attributed by us, keep that attribution.
        try {
            var pd = ent.getPersistentData();
            if (pd != null && (pd.contains("avilixlogger_owner_uuid") || pd.contains("avilixlogger_owner"))) {
                String s = pd.contains("avilixlogger_owner_uuid") ? pd.getString("avilixlogger_owner_uuid") : pd.getString("avilixlogger_owner");
                if (s != null && !s.isBlank()) {
                    java.util.UUID u = null;
                    try { u = java.util.UUID.fromString(s); } catch (Throwable ignored) {}
                    String n = null;
                    try { if (pd.contains("avilixlogger_owner_name")) n = pd.getString("avilixlogger_owner_name"); } catch (Throwable ignored) {}
                    if (u != null || (n != null && !n.isBlank())) {
                        return new ActorTracker.ActorRef(System.currentTimeMillis(), u, n);
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 1) Prefer recent explicit interaction (break / mount / interact).
        ActorTracker.ActorRef r = ActorTracker.getRecent(ent.getUUID(), 5000L);
        if (r != null) return r;

        // 1.25) Generic best-effort recent-action attribution (covers most modded MISC spawns/removals).
        try {
            if (level != null && ent.getType().getCategory() == MobCategory.MISC) {
                RecentPlayerActionTracker.ActionRef rr = RecentPlayerActionTracker.resolveBest(level, ent.blockPosition(), 8, 6000L, null);
                if (rr != null && rr.actorUuid() != null) {
                    // Tag the entity so later removal logs also get attributed.
                    try {
                        ent.getPersistentData().putString("avilixlogger_owner_uuid", rr.actorUuid().toString());
                        if (rr.actorName() != null && !rr.actorName().isBlank()) ent.getPersistentData().putString("avilixlogger_owner_name", rr.actorName());
                        ent.getPersistentData().putInt("avilixlogger_owner_conf", (int) Math.round(rr.confidence() * 100.0));
                        ent.getPersistentData().putString("avilixlogger_owner_src", rr.source());
                    } catch (Throwable ignored2) {}
                    try { ActorTracker.note(ent.getUUID(), rr.actorUuid(), rr.actorName()); } catch (Throwable ignored2) {}
                    return new ActorTracker.ActorRef(System.currentTimeMillis(), rr.actorUuid(), rr.actorName());
                }
            }
        } catch (Throwable ignored) {}

        // 1.5) Planes: attribute spawn to the recent item user (placer), not the owner.
        try {
            if (level != null && com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.isPlaneEntity(ent)) {
                ActorTracker.ActorRef pr = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.consumeRecentPlacer(level, ent);
                if (pr != null) return pr;
            }
        } catch (Throwable ignored) {}

        // 1.75) Create contraptions (trains/contraptions): best-effort attribution via nearby player interaction.
        try {
            var id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(ent.getType());
            if (id != null && "create".equals(id.getNamespace())) {
                CreateOwnershipTracker.ResolvedActor ra = CreateOwnershipTracker.resolveForSystemChange(level, ent.blockPosition(), ent);
                if (ra != null && ra.uuid() != null) {
                    // Tag the entity so later removal logs also get attributed.
                    try {
                        ent.getPersistentData().putString("avilixlogger_owner_uuid", ra.uuid().toString());
                        if (ra.name() != null && !ra.name().isBlank()) ent.getPersistentData().putString("avilixlogger_owner_name", ra.name());
                        ent.getPersistentData().putInt("avilixlogger_owner_conf", 90);
                        ent.getPersistentData().putString("avilixlogger_owner_src", "create_tracker");
                    } catch (Throwable ignored2) {}
                    return new ActorTracker.ActorRef(System.currentTimeMillis(), ra.uuid(), ra.name());
                }
            }
        } catch (Throwable ignored) {}

        // 2) Fallback: read owner info from entity NBT (compat with Immersive Aircraft / Many Planes mixins).
        try {
            if (entitySnbt == null) return null;
            var tag = NbtSerde.fromSnbt(entitySnbt);
            if (tag == null) return null;

            String owner = null;
            for (String k : new String[]{"owner_uuid", "owner", "OwnerUUID", "Owner", "ownerUuid", "ownerUUID"}) {
                if (tag.contains(k)) {
                    owner = tag.getString(k);
                    if (owner != null && !owner.isBlank()) break;
                }
            }
            if (owner == null || owner.isBlank()) return null;

            // owner may be UUID string or player name.
            UUID uuid = null;
            try {
                uuid = UUID.fromString(owner);
            } catch (Throwable ignored) {}

            if (uuid != null) {
                String name = null;
                try {
                    Player p = level.getPlayerByUUID(uuid);
                    if (p != null) name = p.getName().getString();
                } catch (Throwable ignored) {}
                try {
                    if (name == null) {
                        var cache = level.getServer() != null ? level.getServer().getProfileCache() : null;
                        if (cache != null) {
                            var opt = cache.get(uuid);
                            if (opt != null && opt.isPresent()) name = opt.get().getName();
                        }
                    }
                } catch (Throwable ignored) {}
                return new ActorTracker.ActorRef(System.currentTimeMillis(), uuid, name);
            }

            // name only
            return new ActorTracker.ActorRef(System.currentTimeMillis(), null, owner);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int getPickupDelayCompat(ItemEntity ent) {
        if (ent == null) return 0;
        try {
            // Common method names across mappings.
            for (String name : new String[]{"getPickupDelay", "getPickUpDelay"}) {
                try {
                    Method m = ent.getClass().getMethod(name);
                    Object r = m.invoke(ent);
                    if (r instanceof Number n) return n.intValue();
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        }
        try {
            // Fallback to field access.
            var f = ent.getClass().getDeclaredField("pickupDelay");
            f.setAccessible(true);
            Object r = f.get(ent);
            if (r instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        return 0;
    }

    private static int getEntityAgeTicksCompat(Entity ent) {
        if (ent == null) return -1;
        try {
            // Vanilla field name.
            var f = ent.getClass().getDeclaredField("tickCount");
            f.setAccessible(true);
            Object r = f.get(ent);
            if (r instanceof Number n) return n.intValue();
        } catch (Throwable ignored) {
        }
        try {
            // Some mappings expose getter.
            for (String name : new String[]{"getTickCount", "tickCount", "getAge"}) {
                try {
                    Method m = ent.getClass().getMethod(name);
                    Object r = m.invoke(ent);
                    if (r instanceof Number n) return n.intValue();
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {
        }
        return -1;
    }

    @SubscribeEvent
    public void onEntityLeave(EntityLeaveLevelEvent event) {
        if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        Entity ent = event.getEntity();
        if (ent == null) return;
        if (ent instanceof Player) return;

        // Non-living / misc entities (vehicles, plane mods, etc.) are not caught by LivingDeathEvent.
        // We capture removals for minecarts and other MISC entities.
        if (!(ent instanceof AbstractMinecart) && ent.getType().getCategory() != MobCategory.MISC) return;
        if (ent instanceof Projectile || ent instanceof ExperienceOrb || ent instanceof ItemEntity) return;

        // Отфильтровываем выгрузку чанка/переход измерений и т.п. — логируем только реальные удаления.
        try {
            Entity.RemovalReason rr = ent.getRemovalReason();
            if (rr != Entity.RemovalReason.KILLED && rr != Entity.RemovalReason.DISCARDED) return;
        } catch (Throwable ignored) {
            // Если API изменилось, лучше не шуметь в логах.
            return;
        }

        boolean isPlane = false;
        try { isPlane = AirplanesCompatHooks.isPlaneEntity(ent); } catch (Throwable ignored) {}

        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        // AirPlanesLogger parity: plane removal is its own type.
        e.type = isPlane ? ActionType.PLANE_REMOVE : ActionType.ENTITY_DEATH;
        e.actorUuid = null;
        e.actorName = null;
        e.x = ent.blockPosition().getX();
        e.y = ent.blockPosition().getY();
        e.z = ent.blockPosition().getZ();
        e.entityType = EntityType.getKey(ent.getType()).toString();
        e.entityUuid = ent.getUUID();
        e.entityNbt = NbtSerde.writeEntity(level, ent);

        ActorTracker.ActorRef ar = resolveActorForEntity(level, ent, e.entityNbt);
        if (ar != null) {
            e.actorUuid = ar.actorUuid();
            e.actorName = ar.actorName();
        }

        if (isPlane) {
            // Store owner + cause in extra for offline filtering.
            String ownerName = null;
            String ownerUuid = null;
            try {
                var tag = NbtSerde.fromSnbt(e.entityNbt);
                ownerName = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerNameFromTag(tag);
                ownerUuid = com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks.ownerUuidFromTag(tag);
            } catch (Throwable ignored) {}
            String cause = null;
            try { cause = CauseTracker.pop(ent.getUUID()); } catch (Throwable ignored) {}
            String safeName = (ownerName == null ? "" : ownerName.replace("\"", "\\\""));
            String safeUuid = (ownerUuid == null ? "" : ownerUuid);
            String safeCause = (cause == null ? "" : cause.replace("\"", "\\\""));
            e.extra = "{\"kind\":\"plane_remove\",\"ownerName\":\"" + safeName + "\",\"ownerUuid\":\"" + safeUuid + "\",\"cause\":\"" + safeCause + "\"}";
        } else {
            e.extra = "remove " + e.entityType;
        }
        LoggerRuntime.storage(level).append(e);
    }


}