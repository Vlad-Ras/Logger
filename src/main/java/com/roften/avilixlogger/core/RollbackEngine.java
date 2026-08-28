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
        java.util.HashMap<BlockPos, BlockSnapshot> blockSnapshots = new java.util.HashMap<>();
        java.util.ArrayList<LogEntry> entityRemovals = new java.util.ArrayList<>();
        java.util.ArrayList<LogEntry> deferred = new java.util.ArrayList<>();

        long beforeId = Long.MAX_VALUE;
        while (true) {
            q.beforeId = beforeId;
            q.limit = PAGE;
            q.requireDetails = true;
            List<LogEntry> batch = LoggerRuntime.storage(level).queryReverse(q);
            if (batch.isEmpty()) break;

            for (LogEntry e : batch) {
                if (isBlockRestoreType(e.type)) {
                    BlockPos pos = new BlockPos(e.x, e.y, e.z);
                    // Overwrite as we go backwards in time; the last value wins => oldest snapshot.
                    blockSnapshots.put(pos, new BlockSnapshot(e.blockBefore, e.beBefore, e.containerSlotsBefore, e.type));
                } else if (isEntityRemovalType(e.type)) {
                    // Undo spawned moving entities before stationary blocks are put back.
                    entityRemovals.add(e);
                } else {
                    deferred.add(e);
                }
            }

            beforeId = batch.get(batch.size() - 1).id;
            if (batch.size() < PAGE) break;
        }

        // Safe order: restore stationary blocks and their payloads transactionally, then remove
        // entities created by assembly, then respawn killed entities/Create structures.
        // A failed block transaction therefore cannot leave a required moving entity deleted.
        applyConsolidatedBlocks(level, blockSnapshots, report, apply);
        for (LogEntry entry : entityRemovals) applyRollback(level, entry, report, apply);
        for (LogEntry entry : deferred) applyRollback(level, entry, report, apply);
        return report;
    }

    private static void applyConsolidatedBlocks(ServerLevel level, java.util.Map<BlockPos, BlockSnapshot> snapshots,
                                                RollbackReport report, boolean apply) {
        if (snapshots == null || snapshots.isEmpty()) return;
        java.util.ArrayList<java.util.Map.Entry<BlockPos, BlockSnapshot>> ordered = new java.util.ArrayList<>(snapshots.entrySet());
        ordered.sort((a, b) -> {
            BlockPos pa = a.getKey();
            BlockPos pb = b.getKey();
            int cy = Integer.compare(pa.getY(), pb.getY());
            if (cy != 0) return cy;
            int cx = Integer.compare(pa.getX(), pb.getX());
            return cx != 0 ? cx : Integer.compare(pa.getZ(), pb.getZ());
        });

        java.util.ArrayList<PreparedBlock> prepared = new java.util.ArrayList<>(ordered.size());
        for (var entry : ordered) {
            BlockPos pos = entry.getKey();
            BlockSnapshot snap = entry.getValue();
            if (pos == null || snap == null) continue;
            if (!level.hasChunkAt(pos)) { report.onSkipped("chunk_unloaded"); continue; }

            BlockState state = null;
            if (snap.blockBeforeSnbt != null) {
                state = NbtSerde.readBlockState(level, snap.blockBeforeSnbt);
                if (state == null) { report.onSkipped("invalid_block_snapshot"); continue; }
            }
            if (snap.beBeforeSnbt != null && NbtSerde.fromSnbt(snap.beBeforeSnbt) == null) {
                report.onSkipped("invalid_block_entity_nbt");
                continue;
            }
            if (snap.containerSlotsBeforeSnbt != null && !ContainerSlotSnapshot.isValid(snap.containerSlotsBeforeSnbt)) {
                report.onSkipped("invalid_container_snapshot");
                continue;
            }
            if (state == null && snap.beBeforeSnbt == null && snap.containerSlotsBeforeSnbt == null) {
                report.onSkipped("no_snapshot");
                continue;
            }
            prepared.add(new PreparedBlock(pos, snap, state));
        }

        if (!apply) {
            for (PreparedBlock block : prepared) countPreparedBlock(report, block);
            return;
        }

        java.util.ArrayList<BlockBackup> backups = new java.util.ArrayList<>(prepared.size());
        for (PreparedBlock block : prepared) {
            backups.add(new BlockBackup(block.pos, level.getBlockState(block.pos),
                    NbtSerde.writeBlockEntity(level, level.getBlockEntity(block.pos)),
                    ContainerSlotSnapshot.snapshot(level, block.pos)));
        }

        String failure = null;
        java.util.HashSet<BlockPos> createTouched = new java.util.HashSet<>();
        for (PreparedBlock block : prepared) {
            if (block.state == null) continue;
            try {
                level.setBlock(block.pos, block.state, rollbackSetBlockFlags(block.state));
                if (!level.getBlockState(block.pos).equals(block.state)) { failure = "block_state_verification_failed"; break; }
                if (isCreateState(block.state) || looksLikeCreateStateSnbt(block.snapshot.blockBeforeSnbt)) createTouched.add(block.pos);
            } catch (Throwable ignored) {
                failure = "block_state_restore_failed";
                break;
            }
        }

        if (failure == null) {
            for (PreparedBlock block : prepared) {
                if (block.snapshot.beBeforeSnbt != null) {
                    if (!NbtSerde.readBlockEntity(level, block.pos, block.snapshot.beBeforeSnbt)) {
                        failure = "block_entity_restore_failed";
                        break;
                    }
                    if (looksLikeCreatePayload(block.snapshot.beBeforeSnbt)) createTouched.add(block.pos);
                }
                if (block.snapshot.containerSlotsBeforeSnbt != null) {
                    if (!ContainerSlotSnapshot.apply(level, block.pos, block.snapshot.containerSlotsBeforeSnbt)) {
                        failure = "container_restore_failed";
                        break;
                    }
                    if (isCreateState(level.getBlockState(block.pos))) createTouched.add(block.pos);
                }
            }
        }

        if (failure != null) {
            restoreBlockBackups(level, backups);
            for (int i = 0; i < prepared.size(); i++) report.onSkipped(failure);
            return;
        }

        for (PreparedBlock block : prepared) countPreparedBlock(report, block);
        for (BlockPos pos : createTouched) {
            try {
                BlockState state = level.getBlockState(pos);
                level.sendBlockUpdated(pos, state, state, 3);
                level.blockUpdated(pos, state.getBlock());
                level.updateNeighborsAt(pos, state.getBlock());
            } catch (Throwable ignored) {}
        }
    }

    private static void countPreparedBlock(RollbackReport report, PreparedBlock block) {
        if (block.state != null) report.blocksRestored++;
        if (block.snapshot.beBeforeSnbt != null) report.blockEntitiesRestored++;
        if (block.snapshot.containerSlotsBeforeSnbt != null) report.containersRestored++;
        report.onApplied(block.snapshot.type);
    }

    private static void restoreBlockBackups(ServerLevel level, java.util.List<BlockBackup> backups) {
        for (BlockBackup backup : backups) {
            try { level.setBlock(backup.pos, backup.state, 2); } catch (Throwable ignored) {}
        }
        for (BlockBackup backup : backups) {
            try {
                if (backup.beSnbt != null) NbtSerde.readBlockEntity(level, backup.pos, backup.beSnbt);
                if (backup.containerSnbt != null) ContainerSlotSnapshot.apply(level, backup.pos, backup.containerSnbt);
            } catch (Throwable ignored) {}
        }
    }

    private static boolean isEntityRemovalType(ActionType type) {
        return type == ActionType.ENTITY_SPAWN || type == ActionType.PLANE_PLACE;
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

    private record PreparedBlock(BlockPos pos, BlockSnapshot snapshot, BlockState state) {}
    private record BlockBackup(BlockPos pos, BlockState state, String beSnbt, String containerSnbt) {}

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
                case ENTITY_DEATH, PLANE_REMOVE -> {
                    if (e.entityType != null && e.entityNbt != null) {
                        String invalid = NbtSerde.validateEntitySnapshot(e.entityType, e.entityUuid, e.entityNbt);
                        if (invalid != null) { report.onSkipped(invalid); return; }
                        if (!apply) {
                            if (NbtSerde.isCreateContraptionSnapshot(e.entityType, e.entityNbt)) report.createStructuresRestored++;
                            else report.entitiesRespawned++;
                            report.onApplied(e.type);
                            return;
                        }

                        boolean createSnapshot = NbtSerde.isCreateContraptionSnapshot(e.entityType, e.entityNbt);
                        NbtSerde.EntityRestoreResult result = createSnapshot
                                ? NbtSerde.restoreCreateContraptionAsBlocks(level, e.entityType, e.entityUuid, e.entityNbt)
                                : NbtSerde.restoreEntityFromSnapshot(level, e.entityType, e.entityUuid, e.entityNbt);
                        if (!result.success()) { report.onSkipped(result.reason()); return; }
                        if (createSnapshot) {
                            report.createStructuresRestored++;
                            report.createBlocksRestored += result.affected();
                        } else {
                            report.entitiesRespawned += result.affected();
                        }
                        report.onApplied(e.type);
                        return;
                    }
                    report.onSkipped("no_entity_snapshot");
                    return;
                }
                case ENTITY_SPAWN, PLANE_PLACE -> {
                    if (e.entityUuid != null) {
                        Entity ent = level.getEntity(e.entityUuid);
                        if (ent != null) {
                            java.util.List<Entity> tree = ent.getSelfAndPassengers().toList();
                            int affected = tree.size();
                            if (apply) {
                                for (int i = tree.size() - 1; i >= 0; i--) tree.get(i).discard();
                                boolean remains = false;
                                for (Entity member : tree) {
                                    if (level.getEntity(member.getUUID()) != null) { remains = true; break; }
                                }
                                if (remains) {
                                    report.onSkipped("entity_remove_failed");
                                    return;
                                }
                            }
                            report.entitiesRemoved += Math.max(1, affected);
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
                case ITEM_PICKUP, PLANE_PICKUP -> {
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
