package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.UUID;

/**
 * Rollback engine.
 *
 * Strategy:
 * - For block actions, restore block state and block entity NBT (if present).
 * - For container actions, restoring block entity NBT also restores its inventory.
 * - For item pick/drop, best-effort adjustments to the player's inventory (if online).
 */
public final class RollbackEngine {

    private static final int PAGE = 2000;

    private RollbackEngine() {}

    public static int rollbackExact(ServerLevel level, BlockPos pos, long sinceTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = sinceTs;
        q.untilTs = System.currentTimeMillis();
        q.exactPos = pos;
        q.actorName = actorName;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackExactRange(ServerLevel level, BlockPos pos, long fromTs, long untilTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs;
        q.untilTs = untilTs;
        q.exactPos = pos;
        q.actorName = actorName;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackBox(ServerLevel level, BlockPos min, BlockPos max, long sinceTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = sinceTs;
        q.untilTs = System.currentTimeMillis();
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        q.actorName = actorName;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackBoxRange(ServerLevel level, BlockPos min, BlockPos max, long fromTs, long untilTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        q.actorName = actorName;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    /**
     * Same as {@link #rollbackBoxRange(ServerLevel, BlockPos, BlockPos, long, long, String)}, but returns a detailed report.
     */
    public static RollbackReport rollbackBoxRangeReport(ServerLevel level, BlockPos min, BlockPos max, long fromTs, long untilTs, String actorName, java.util.EnumSet<ActionType> types) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        q.actorName = actorName;
        q.types = types;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true);
    }

    /**
     * Report-producing rollback for open-ended (sinceTs..now).
     */
    public static RollbackReport rollbackBoxReport(ServerLevel level, BlockPos min, BlockPos max, long sinceTs, String actorName, java.util.EnumSet<ActionType> types) {
        return rollbackBoxRangeReport(level, min, max, sinceTs, System.currentTimeMillis(), actorName, types);
    }

    /**
     * Preview variant (no world changes): computes what would be affected.
     */
    public static RollbackReport previewBoxRangeReport(ServerLevel level, BlockPos min, BlockPos max, long fromTs, long untilTs, String actorName, java.util.EnumSet<ActionType> types) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        q.actorName = actorName;
        q.types = types;
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, false);
    }

    /**
     * Preview variant (sinceTs..now).
     */
    public static RollbackReport previewBoxReport(ServerLevel level, BlockPos min, BlockPos max, long sinceTs, String actorName, java.util.EnumSet<ActionType> types) {
        return previewBoxRangeReport(level, min, max, sinceTs, System.currentTimeMillis(), actorName, types);
    }

    private static RollbackReport rollbackByQueryPaged(ServerLevel level, LogQuery q, boolean apply) {
        RollbackReport report = new RollbackReport();
        // Optimization:
        // For block/BE-related actions we only need the oldest "before" snapshot per position.
        // Applying per-entry creates huge lag for radius rollbacks (same position may be written many times).
        // We therefore collect a final snapshot per position and apply once at the end.
        java.util.HashMap<BlockPos, BlockSnapshot> blockSnapshots = apply ? new java.util.HashMap<>() : null;

        long beforeId = Long.MAX_VALUE;
        while (true) {
            q.beforeId = beforeId;
            q.limit = PAGE;
            q.requireDetails = true;
            List<LogEntry> batch = LoggerRuntime.storage(level).queryReverse(q);
            if (batch.isEmpty()) break;

            for (LogEntry e : batch) {
                if (apply && isBlockRestoreType(e.type)) {
                    BlockPos pos = new BlockPos(e.x, e.y, e.z);
                    // Overwrite as we go backwards in time; the last value wins => oldest snapshot.
                    blockSnapshots.put(pos, new BlockSnapshot(e.blockBefore, e.beBefore, e.containerSlotsBefore, e.type));
                } else {
                    applyRollback(level, e, report, apply);
                }
            }

            beforeId = batch.get(batch.size() - 1).id;
            if (batch.size() < PAGE) break;
        }

        // Apply consolidated snapshots in phases:
        // 1) all blockstates first
        // 2) all block entities / inventories second
        // This is much safer for Create and other multiblock/modded blocks than restoring one position fully at a time.
        if (apply && blockSnapshots != null && !blockSnapshots.isEmpty()) {
            java.util.ArrayList<java.util.Map.Entry<BlockPos, BlockSnapshot>> ordered = new java.util.ArrayList<>(blockSnapshots.entrySet());
            ordered.sort((a, b) -> {
                BlockPos pa = a.getKey();
                BlockPos pb = b.getKey();
                int cy = Integer.compare(pa.getY(), pb.getY());
                if (cy != 0) return cy;
                int cx = Integer.compare(pa.getX(), pb.getX());
                if (cx != 0) return cx;
                return Integer.compare(pa.getZ(), pb.getZ());
            });

            java.util.HashSet<BlockPos> createTouched = new java.util.HashSet<>();
            java.util.HashSet<BlockPos> appliedAny = new java.util.HashSet<>();

            for (var ent : ordered) {
                BlockPos pos = ent.getKey();
                BlockSnapshot snap = ent.getValue();
                if (pos == null || snap == null) continue;
                if (!level.hasChunkAt(pos)) { report.onSkipped("chunk_unloaded"); continue; }

                if (snap.blockBeforeSnbt != null) {
                    BlockState before = NbtSerde.readBlockState(level, snap.blockBeforeSnbt);
                    if (before != null) {
                        int flags = rollbackSetBlockFlags(before);
                        level.setBlock(pos, before, flags);
                        report.blocksRestored++;
                        appliedAny.add(pos);
                        if (isCreateState(before) || looksLikeCreateStateSnbt(snap.blockBeforeSnbt)) createTouched.add(pos.immutable());
                    }
                }
            }

            for (var ent : ordered) {
                BlockPos pos = ent.getKey();
                BlockSnapshot snap = ent.getValue();
                if (pos == null || snap == null) continue;
                if (!level.hasChunkAt(pos)) continue;

                boolean any = appliedAny.contains(pos);
                if (snap.beBeforeSnbt != null) {
                    NbtSerde.readBlockEntity(level, pos, snap.beBeforeSnbt);
                    report.blockEntitiesRestored++;
                    any = true;
                    if (looksLikeCreatePayload(snap.beBeforeSnbt)) createTouched.add(pos.immutable());
                }
                if (snap.containerSlotsBeforeSnbt != null) {
                    ContainerSlotSnapshot.apply(level, pos, snap.containerSlotsBeforeSnbt);
                    report.containersRestored++;
                    any = true;
                    if (isCreateState(level.getBlockState(pos))) createTouched.add(pos.immutable());
                }
                if (any) report.onApplied(snap.type);
                else report.onSkipped("no_snapshot");
            }

            if (!createTouched.isEmpty()) {
                for (BlockPos pos : createTouched) {
                    if (pos == null || !level.hasChunkAt(pos)) continue;
                    try {
                        BlockState st = level.getBlockState(pos);
                        if (st == null) continue;
                        level.sendBlockUpdated(pos, st, st, 3);
                        level.blockUpdated(pos, st.getBlock());
                        level.updateNeighborsAt(pos, st.getBlock());
                    } catch (Throwable ignored) {}
                }
            }
        }
        return report;
    }

    private static boolean isBlockRestoreType(ActionType t) {
        return t == ActionType.BLOCK_BREAK
                || t == ActionType.BLOCK_PLACE
                || t == ActionType.BLOCK_INTERACT
                || t == ActionType.BLOCK_ENTITY_NBT_CHANGE
                || t == ActionType.CONTAINER_PUT
                || t == ActionType.CONTAINER_TAKE;
    }

    private static int rollbackSetBlockFlags(BlockState state) {
        return isCreateState(state) ? 3 : 2;
    }

    private static boolean isCreateState(BlockState state) {
        if (state == null) return false;
        try {
            var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
            return id != null && ("create".equals(id.getNamespace()) || "aeronautics".equals(id.getNamespace()));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean looksLikeCreateStateSnbt(String snbt) {
        if (snbt == null || snbt.isBlank()) return false;
        String s = snbt.toLowerCase(java.util.Locale.ROOT);
        return s.contains("\"create:") || s.contains("create:") || s.contains("\"aeronautics:") || s.contains("aeronautics:");
    }

    private static boolean looksLikeCreatePayload(String snbt) {
        if (snbt == null || snbt.isBlank()) return false;
        return snbt.toLowerCase(java.util.Locale.ROOT).contains("create:");
    }

    private static final class BlockSnapshot {
        final String blockBeforeSnbt;
        final String beBeforeSnbt;
        final String containerSlotsBeforeSnbt;
        final ActionType type;
        BlockSnapshot(String blockBeforeSnbt, String beBeforeSnbt, String containerSlotsBeforeSnbt, ActionType type) {
            this.blockBeforeSnbt = blockBeforeSnbt;
            this.beBeforeSnbt = beBeforeSnbt;
            this.containerSlotsBeforeSnbt = containerSlotsBeforeSnbt;
            this.type = type;
        }
    }

    private static void applyRollback(ServerLevel level, LogEntry e, RollbackReport report, boolean apply) {
        try {
            BlockPos pos = new BlockPos(e.x, e.y, e.z);

            // In preview mode we want to be explicit about unloaded chunks.
            if ((e.type == ActionType.BLOCK_BREAK || e.type == ActionType.BLOCK_PLACE || e.type == ActionType.BLOCK_INTERACT ||
                    e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE || e.type == ActionType.CONTAINER_PUT || e.type == ActionType.CONTAINER_TAKE)
                    && !level.hasChunkAt(pos)) {
                report.onSkipped("chunk_unloaded");
                return;
            }
            switch (e.type) {
                case BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_ENTITY_NBT_CHANGE, CONTAINER_PUT, CONTAINER_TAKE -> {
                    // Restore blockstate + BE snapshot (if we have it).
                    boolean any = false;
                    if (e.blockBefore != null) {
                        BlockState before = NbtSerde.readBlockState(level, e.blockBefore);
                        if (before != null) {
                            // Minimal flags (client update only) to reduce lag.
                            if (apply) level.setBlock(pos, before, rollbackSetBlockFlags(before));
                            report.blocksRestored++;
                            any = true;
                        }
                    }
                    if (e.beBefore != null) {
                        if (apply) NbtSerde.readBlockEntity(level, pos, e.beBefore);
                        report.blockEntitiesRestored++;
                        any = true;
                    }
                    if (e.containerSlotsBefore != null && !e.containerSlotsBefore.isBlank()) {
                        if (apply) ContainerSlotSnapshot.apply(level, pos, e.containerSlotsBefore);
                        report.containersRestored++;
                        any = true;
                    }
                    if (any) report.onApplied(e.type);
                    else report.onSkipped("no_snapshot");
                    return;
                }
                case ENTITY_DEATH -> {
                    // Best-effort respawn. Create contraptions are special-cased:
                    // instead of respawning the moving entity we ask Create to place the
                    // contraption blocks back into the world from the saved snapshot.
                    if (e.entityType != null && e.entityNbt != null) {
                        if (apply && NbtSerde.restoreCreateContraptionAsBlocks(level, e.entityType, e.entityNbt, e.x + 0.5, e.y, e.z + 0.5)) {
                            report.onApplied(e.type);
                            return;
                        }

                        Entity spawned = null;
                        if (apply) spawned = NbtSerde.spawnEntityFromSnapshot(level, e.entityType, e.entityNbt, e.x + 0.5, e.y, e.z + 0.5);
                        if (!apply || spawned != null) {
                            report.entitiesRespawned++;
                            report.onApplied(e.type);
                        } else {
                            report.onSkipped("unsafe_entity_snapshot");
                        }
                        return;
                    }
                    report.onSkipped("no_entity_snapshot");
                    return;
                }
                case ENTITY_SPAWN -> {
                    // Remove spawned entity (only if we can find it). Best-effort.
                    if (e.entityUuid != null) {
                        Entity ent = level.getEntity(e.entityUuid);
                        if (ent != null) {
                            if (apply) ent.discard();
                            report.entitiesRemoved++;
                            report.onApplied(e.type);
                            return;
                        }
                        report.onSkipped("entity_not_found");
                        return;
                    }
                    report.onSkipped("missing_uuid");
                    return;
                }
                case ITEM_DROP -> {
                    ItemStack st = NbtSerde.readItemStack(e.itemStackNbt, level.registryAccess());
                    if (st.isEmpty()) { report.onSkipped("invalid_stack"); return; }
                    st.setCount(Math.max(1, e.count));
                    ServerPlayer p = (e.actorUuid != null) ? level.getServer().getPlayerList().getPlayer(e.actorUuid) : null;
                    if (p != null) {
                        if (apply) {
                            boolean ok = p.getInventory().add(st.copy());
                            if (!ok) {
                                level.addFreshEntity(new ItemEntity(level, p.getX(), p.getY(), p.getZ(), st.copy()));
                            }
                        }
                        report.itemsGivenOrSpawned++;
                        report.onApplied(e.type);
                        return;
                    }
                    // If player is offline, just spawn item at original position.
                    if (apply) level.addFreshEntity(new ItemEntity(level, e.x + 0.5, e.y + 0.5, e.z + 0.5, st.copy()));
                    report.itemsGivenOrSpawned++;
                    report.onApplied(e.type);
                    return;
                }
                case ITEM_PICKUP -> {
                    // Remove picked item from inventory (best-effort).
                    ItemStack target = NbtSerde.readItemStack(e.itemStackNbt, level.registryAccess());
                    if (target.isEmpty()) { report.onSkipped("invalid_stack"); return; }
                    target.setCount(Math.max(1, e.count));
                    ServerPlayer p = (e.actorUuid != null) ? level.getServer().getPlayerList().getPlayer(e.actorUuid) : null;
                    if (p == null) { report.onSkipped("player_offline"); return; }
                    int removed;
                    if (apply) {
                        removed = ItemRollbackUtil.removeMatching(p, target, target.getCount());
                        if (removed < target.getCount()) {
                            // Couldn't fully remove: spawn remainder to avoid duping.
                            ItemStack rem = target.copy();
                            rem.setCount(target.getCount() - removed);
                            level.addFreshEntity(new ItemEntity(level, p.getX(), p.getY(), p.getZ(), rem));
                            report.itemsGivenOrSpawned++;
                        }
                    } else {
                        int available = ItemRollbackUtil.countMatching(p, target);
                        removed = Math.min(available, target.getCount());
                        if (removed < target.getCount()) {
                            report.itemsGivenOrSpawned++; // would spawn remainder
                        }
                    }
                    report.itemsRemovedFromInventory += removed;
                    report.onApplied(e.type);
                    return;
                }
                case ITEM_CRAFT, ITEM_SMELT -> {
                    // Best-effort: remove crafted output from inventory if player online.
                    ItemStack out = NbtSerde.readItemStack(e.itemStackNbt, level.registryAccess());
                    if (out.isEmpty()) { report.onSkipped("invalid_stack"); return; }
                    out.setCount(Math.max(1, e.count));
                    ServerPlayer p = (e.actorUuid != null) ? level.getServer().getPlayerList().getPlayer(e.actorUuid) : null;
                    if (p == null) { report.onSkipped("player_offline"); return; }
                    if (apply) ItemRollbackUtil.removeMatching(p, out, out.getCount());
                    report.itemsRemovedFromInventory += out.getCount();
                    report.onApplied(e.type);
                    return;
                }
            }

            // If we reached here, the action type is not rollbackable by this engine.
            report.onSkipped("not_rollbackable");
            return;
        } catch (Throwable t) {
            report.onSkipped("exception");
        }
    }
}
