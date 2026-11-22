package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
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

    private static void setActors(LogQuery query,String names) {
        if (names!=null && names.contains(",")) {
            var actors=java.util.Arrays.stream(names.split(",",-1)).map(String::trim).toList();
            if (actors.size()>16 || actors.stream().anyMatch(String::isEmpty))
                throw new IllegalArgumentException("Укажите до 16 непустых имён игроков через запятую");
            query.actorNames=actors;
        } else query.actorName=names;
    }


    private RollbackEngine() {}

    public static int rollbackExact(ServerLevel level, BlockPos pos, long sinceTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = sinceTs == Long.MAX_VALUE ? Long.MAX_VALUE : sinceTs + 1;
        q.untilTs = System.currentTimeMillis();
        q.exactPos = pos;
        setActors(q, actorName);
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackExactRange(ServerLevel level, BlockPos pos, long fromTs, long untilTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs == Long.MAX_VALUE ? Long.MAX_VALUE : fromTs + 1;
        q.untilTs = untilTs;
        q.exactPos = pos;
        setActors(q, actorName);
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackBox(ServerLevel level, BlockPos min, BlockPos max, long sinceTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = sinceTs == Long.MAX_VALUE ? Long.MAX_VALUE : sinceTs + 1;
        q.untilTs = System.currentTimeMillis();
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        setActors(q, actorName);
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    public static int rollbackBoxRange(ServerLevel level, BlockPos min, BlockPos max, long fromTs, long untilTs, String actorName) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs == Long.MAX_VALUE ? Long.MAX_VALUE : fromTs + 1;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        setActors(q, actorName);
        q.limit = PAGE;
        return rollbackByQueryPaged(level, q, true).applied;
    }

    /**
     * Same as {@link #rollbackBoxRange(ServerLevel, BlockPos, BlockPos, long, long, String)}, but returns a detailed report.
     */
    public static RollbackReport rollbackBoxRangeReport(ServerLevel level, BlockPos min, BlockPos max, long fromTs, long untilTs, String actorName, java.util.EnumSet<ActionType> types) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = fromTs == Long.MAX_VALUE ? Long.MAX_VALUE : fromTs + 1;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        setActors(q, actorName);
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
        q.sinceTs = fromTs == Long.MAX_VALUE ? Long.MAX_VALUE : fromTs + 1;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        setActors(q, actorName);
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

    /**
     * Immutable database result used by the tick-budgeted rollback coordinator. It deliberately
     * contains no live world objects, so ClickHouse paging can safely happen on a worker thread.
     */
    public record PreparedBlockSnapshot(BlockPos pos, String blockBeforeSnbt, String beBeforeSnbt,
                                        String containerSlotsBeforeSnbt, ActionType type,
                                        CompoundTag stateTag, CompoundTag beTag, CompoundTag slotsTag, CompoundTag expectedState,
                                        List<ReplayCheckpoint> checkpoints) {
        public PreparedBlockSnapshot(BlockPos pos, String state, String be, String slots, ActionType type,
                                     CompoundTag stateTag, CompoundTag beTag, CompoundTag slotsTag, CompoundTag expectedState) {
            this(pos, state, be, slots, type, stateTag, beTag, slotsTag, expectedState, List.of());
        }
        public PreparedBlockSnapshot(BlockPos pos, String state, String be, String slots, ActionType type) {
            this(pos, state, be, slots, type, NbtSerde.fromSnbt(state), NbtSerde.fromSnbt(be), NbtSerde.fromSnbt(slots), null);
        }
        public PreparedBlockSnapshot(BlockPos pos, String state, String be, String slots, ActionType type,
                                     CompoundTag stateTag, CompoundTag beTag, CompoundTag slotsTag) {
            this(pos, state, be, slots, type, stateTag, beTag, slotsTag, null);
        }
        public PreparedBlockSnapshot {
            pos = pos == null ? BlockPos.ZERO : pos.immutable();
            checkpoints = checkpoints == null ? List.of() : List.copyOf(checkpoints);
        }
    }

    /** A detached full block checkpoint; null BE/slots means absent, not a partial patch. */
    public record ReplayCheckpoint(CompoundTag state, CompoundTag be, CompoundTag slots) {}

    public record PreparedRollback(List<PreparedBlockSnapshot> blocks,
                                   List<LogEntry> entityRemovals,
                                   List<LogEntry> deferred,
                                   long estimatedBytes, java.util.Map<String, Integer> preparationSkips,
                                   List<EntityReplayPlan.Prepared> entityReplays,List<EntityReplayPlan.Issue> preparationIssues,
                                   List<PlayerBlockTransfer.Proof> playerTransfers) {
        public PreparedRollback(List<PreparedBlockSnapshot> blocks,List<LogEntry> removals,List<LogEntry> deferred,long bytes,
                                java.util.Map<String,Integer> skips,List<EntityReplayPlan.Prepared> entities,List<EntityReplayPlan.Issue> issues) {
            this(blocks,removals,deferred,bytes,skips,entities,issues,List.of());
        }
        public PreparedRollback(List<PreparedBlockSnapshot> blocks,List<LogEntry> removals,List<LogEntry> deferred,long bytes,java.util.Map<String,Integer> skips) {
            this(blocks,removals,deferred,bytes,skips,List.of(),List.of());
        }
        public PreparedRollback(List<PreparedBlockSnapshot> blocks, List<LogEntry> removals, List<LogEntry> deferred, long bytes) {
            this(blocks, removals, deferred, bytes, java.util.Map.of());
        }
        public PreparedRollback {
            entityReplays=entityReplays==null?List.of():List.copyOf(entityReplays);
            preparationIssues=preparationIssues==null?List.of():List.copyOf(preparationIssues);
            playerTransfers=playerTransfers==null?List.of():List.copyOf(playerTransfers);
            preparationSkips = preparationSkips == null ? java.util.Map.of() : java.util.Map.copyOf(preparationSkips);
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            entityRemovals = entityRemovals == null ? List.of() : List.copyOf(entityRemovals);
            deferred = deferred == null ? List.of() : List.copyOf(deferred);
        }

        public int operationCount() {
            return blocks.size() + entityRemovals.size() + deferred.size()+entityReplays.size();
        }
    }

    public static PreparedRollback prepareBoxRange(LogStorage storage, String dimension,
                                                    BlockPos min, BlockPos max,
                                                    long fromTs, long untilTs, String actorName,
                                                    java.util.EnumSet<ActionType> types) {
        return prepareBoxRange(storage, dimension, min, max, fromTs, untilTs, actorName, types, () -> false);
    }

    public static PreparedRollback prepareBoxRange(LogStorage storage, String dimension, BlockPos min, BlockPos max,
                                                    long fromTs, long untilTs, String actorName,
                                                    java.util.EnumSet<ActionType> types, java.util.function.BooleanSupplier cancelled) {
        if (storage == null) throw new IllegalArgumentException("storage");
        LogQuery q = new LogQuery();
        q.dim = dimension;
        q.sinceTs = fromTs == Long.MAX_VALUE ? Long.MAX_VALUE : fromTs + 1;
        q.untilTs = untilTs;
        q.minPos = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()), Math.min(min.getZ(), max.getZ()));
        q.maxPos = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()), Math.max(min.getZ(), max.getZ()));
        setActors(q, actorName);
        q.types = types == null ? null : types.clone();
        q.limit = PAGE;
        q.debugSource = "rollback_prepare";
        return prepareByQueryPaged(storage, q, cancelled);
    }

    private record ReplayReceiptKey(BlockPos pos, long ts, String source, UUID actor) {}

    private record ReplayChain(java.util.ArrayList<ReplayCheckpoint> checkpoints, ActionType type) {
        ReplayCheckpoint first() { return checkpoints.getFirst(); }
        ReplayCheckpoint last() { return checkpoints.getLast(); }
    }

    public static PreparedRollback prepareBlockReplay(LogStorage storage, RollbackPlanManager.Plan plan,
                                                      RollbackPlanManager.Scope scope, java.util.function.BooleanSupplier cancelled) {
        var query = new LogQuery();
        query.dim = scope.dimension();
        query.minPos = scope.min();
        query.maxPos = scope.max();
        query.sinceTs = plan.targetTs();
        query.untilTs = plan.cutoffTs();
        // Read all actors: otherwise another player's change can disappear from the chain.
        query.types = java.util.EnumSet.of(ActionType.BLOCK_PLACE, ActionType.BLOCK_BREAK,
                ActionType.BLOCK_ENTITY_NBT_CHANGE, ActionType.CONTAINER_PUT, ActionType.CONTAINER_TAKE,
                ActionType.ENTITY_SPAWN,ActionType.ENTITY_DEATH,ActionType.PLANE_PLACE,ActionType.PLANE_REMOVE);
        query.requireDetails = true;
        query.chronological = true;
        query.limit = PAGE;
        query.debugSource = "block_replay_prepare";
        var actors = plan.actor() == null || plan.actor().isBlank() ? java.util.Set.<String>of()
                : new java.util.HashSet<>(java.util.Arrays.asList(plan.actor().toLowerCase(java.util.Locale.ROOT).split(",")));
        var entityBuilder=new EntityReplayPlan();
        var playerTransfers=new java.util.ArrayList<PlayerBlockTransfer.Proof>();
        var chains = new java.util.LinkedHashMap<BlockPos, ReplayChain>();
        var rejected = new java.util.HashMap<BlockPos, String>();
        var evidenceRecords=new java.util.HashMap<BlockPos,Long>();
        var selected = new java.util.HashSet<BlockPos>();
        var receipts = new java.util.HashSet<ReplayReceiptKey>();
        var fullContainerSnapshots = new java.util.HashSet<ReplayReceiptKey>();
        var skips = new java.util.HashMap<String, Integer>();
        var seenIds = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        int examined = 0;
        long retained = 0;
        long limit;
        try { limit = LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get() * 1024L * 1024L; }
        catch (IllegalStateException unloadedConfig) { limit = 512L * 1024L * 1024L; }
        var types = plan.types();
        while (!cancelled.getAsBoolean()) {
            var rows = storage.query(query);
            if (rows.isEmpty()) break;
            for (var row : rows) {
                if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                if (++examined > 100_000) throw new IllegalStateException("Слишком много записей для восстановления. Сузьте период или область.");
                if (row.id > 0 && !seenIds.add(row.id)) continue;
                retained += 24;
                var pos = new BlockPos(row.x, row.y, row.z);
                boolean matches = (actors.isEmpty() || row.actorName != null && actors.contains(row.actorName.toLowerCase(java.util.Locale.ROOT)))
                        && (types == null || types.contains(row.type));
                if(EntityReplayPlan.handles(row.type)) {
                    entityBuilder.accept(row,matches);
                    if(retained+entityBuilder.estimatedBytes()>limit)throw new IllegalStateException("План сущностей превышает лимит памяти");
                    continue;
                }
                if (matches) selected.add(pos);
                if (rejected.containsKey(pos)) continue;
                evidenceRecords.merge(pos,row.id,Math::max);
                var receiptKey = new ReplayReceiptKey(pos, row.ts, row.source, row.actorUuid);
                if ((row.type == ActionType.CONTAINER_PUT || row.type == ActionType.CONTAINER_TAKE)
                        && row.beBefore == null && row.containerSlotsBefore == null) {
                    // Slot receipts from a menu action are emitted before its full NBT checkpoint.
                    // Pair by exact action identity after paging, never treat the delta as full NBT.
                    if (receipts.add(receiptKey)) retained += 160;
                    if (retained > limit) throw new IllegalStateException("Доказательства контейнеров превышают лимит памяти.");
                    continue;
                }
                var existing = chains.get(pos);
                String beforeState = row.blockBefore != null ? row.blockBefore
                        : row.type == ActionType.BLOCK_ENTITY_NBT_CHANGE ? row.blockAfter : null;
                ReplayCheckpoint before = replayCheckpoint(beforeState, row.beBefore, row.containerSlotsBefore,
                        existing == null ? null : existing.last());
                ReplayCheckpoint after = replayCheckpoint(row.blockAfter, row.beAfter, row.containerSlotsAfter, before);
                String reason = !matches ? "replay_filtered_chain" : before == null || after == null ? "replay_incomplete_snapshot"
                        : CreateContraptionKind.fromSource(row.source) != CreateContraptionKind.UNKNOWN ? "replay_linked_create"
                        : existing != null && !sameHistoryCheckpoint(existing.last(), before) ? "replay_history_gap" : null;
                if (reason != null) {
                    rejected.put(pos, reason);
                    if (chains.remove(pos) != null) retained -= replayWeight(existing);
                    retained += 160; // Keep rejected coordinates bounded as well.
                } else {
                    if (row.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && before.slots() != null && after.slots() != null
                            && fullContainerSnapshots.add(receiptKey)) retained += 160;
                    if (existing == null) {
                        var checkpoints = new java.util.ArrayList<ReplayCheckpoint>();
                        checkpoints.add(before);
                        existing = new ReplayChain(checkpoints, row.type);
                        chains.put(pos, existing);
                        retained += 160 + checkpointWeight(before);
                    }
                    if (existing.type() != row.type) chains.put(pos, new ReplayChain(existing.checkpoints(), row.type));
                    if (!existing.last().equals(after)) {
                        existing.checkpoints().add(after);
                        retained += checkpointWeight(after);
                    }
                    var proof=PlayerBlockTransfer.parse(row,existing.checkpoints().size()-1,before,after);
                    if(proof!=null){playerTransfers.add(proof);retained+=192+PayloadSizeEstimator.estimateTags(proof.before(),proof.after());}
                }
                if (retained+entityBuilder.estimatedBytes() > limit) throw new IllegalStateException("План восстановления превышает лимит памяти.");
            }
            var last = rows.getLast();
            if (rows.size() < PAGE) break;
            if (last.ts < query.cursorTs || last.ts == query.cursorTs && last.id <= query.afterId)
                throw new IllegalStateException("Хранилище не продвинуло курсор восстановления");
            query.afterId = last.id;
            query.cursorTs = last.ts;
        }
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        for (var receipt : receipts) {
            if (!fullContainerSnapshots.contains(receipt)) {
                rejected.put(receipt.pos(), "replay_unpaired_container_delta");
                chains.remove(receipt.pos());
            }
        }
        var blocks = new java.util.ArrayList<PreparedBlockSnapshot>();
        for (var entry : chains.entrySet()) {
            var chain = entry.getValue();
            // A crash may have persisted an intermediate state even if the final state equals the first.
            if (chain.checkpoints().size()==1) continue;
            var target = chain.last();
            blocks.add(new PreparedBlockSnapshot(entry.getKey(), NbtSerde.toSnbt(target.state()), NbtSerde.toSnbt(target.be()),
                    NbtSerde.toSnbt(target.slots()), chain.type(), target.state(), target.be(), target.slots(),
                    chain.first().state(), chain.checkpoints()));
            retained += PayloadSizeEstimator.estimateStrings(blocks.getLast().blockBeforeSnbt(),
                    blocks.getLast().beBeforeSnbt(), blocks.getLast().containerSlotsBeforeSnbt());
            if (retained > limit) throw new IllegalStateException("Разобранный план восстановления превышает лимит памяти.");
        }
        rejected.forEach((pos, reason) -> { if (selected.contains(pos)) skips.merge(reason, 1, Integer::sum); });
        blocks.sort(java.util.Comparator.comparingInt((PreparedBlockSnapshot block) -> block.pos().getX() >> 4)
                .thenComparingInt(block -> block.pos().getZ() >> 4).thenComparingInt(block -> block.pos().getY()));
        var entities=entityBuilder.finish();
        var issues=new java.util.ArrayList<>(entityBuilder.issues());
        for(var issue:issues)if(!issue.conditional())skips.merge(issue.reason(),1,Integer::sum);
        rejected.forEach((pos,reason)->{if(selected.contains(pos))issues.add(new EntityReplayPlan.Issue(scope.dimension(),pos,null,evidenceRecords.getOrDefault(pos,0L),reason));});
        retained+=issues.size()*192L+evidenceRecords.size()*64L;
        if(retained+entityBuilder.estimatedBytes()>limit)throw new IllegalStateException("План с диагностикой превышает лимит памяти");
        var transfers=playerTransfers.stream().map(proof->proof.cutoff(evidenceRecords.getOrDefault(proof.pos(),proof.event()))).toList();
        return new PreparedRollback(blocks, List.of(), List.of(), Math.max(0, retained)+entityBuilder.estimatedBytes(), skips,entities,issues,transfers);
    }

    private static ReplayCheckpoint replayCheckpoint(String state, String be, String slots, ReplayCheckpoint fallback) {
        CompoundTag stateTag = state == null ? fallback == null ? null : fallback.state() : NbtSerde.fromSnbt(state);
        CompoundTag beTag = NbtSerde.fromSnbt(be), slotsTag = NbtSerde.fromSnbt(slots);
        if (stateTag == null || !stateTag.contains("Name", net.minecraft.nbt.Tag.TAG_STRING)
                || be != null && beTag == null || slots != null && !ContainerSlotSnapshot.isValid(slotsTag)) return null;
        // A delta envelope is audit evidence, never a full block-entity checkpoint.
        if (beTag != null && !beTag.contains("id", net.minecraft.nbt.Tag.TAG_STRING)) return null;
        return new ReplayCheckpoint(stateTag, beTag, slotsTag);
    }

    private static boolean sameHistoryCheckpoint(ReplayCheckpoint a, ReplayCheckpoint b) {
        return a.state().equals(b.state()) && java.util.Objects.equals(a.be(), b.be())
                && (a.slots() == null || b.slots() == null || a.slots().equals(b.slots()));
    }

    private static long checkpointWeight(ReplayCheckpoint checkpoint) {
        return 80L + PayloadSizeEstimator.estimateTags(checkpoint.state(), checkpoint.be(), checkpoint.slots());
    }

    private static long replayWeight(ReplayChain chain) {
        long bytes = 160;
        for (var checkpoint : chain.checkpoints()) bytes += checkpointWeight(checkpoint);
        return bytes;
    }

    /** Counts a prepared plan without reading chunks, registries or any other live world state. */
    public static RollbackReport previewPrepared(PreparedRollback prepared) {
        return previewPrepared(prepared, null);
    }

    static RollbackReport previewPrepared(PreparedRollback prepared, java.util.function.Consumer<LogEntry> entityMarkers) {
        RollbackReport report = new RollbackReport();
        if (prepared == null) return report;
        prepared.preparationSkips().forEach((reason, count) -> {
            report.skippedReasons.merge(reason, count, Integer::sum);
            report.processed += count;
            report.skipped += count;
        });
        for (PreparedBlockSnapshot block : prepared.blocks()) {
            boolean any = block.blockBeforeSnbt() != null || block.beBeforeSnbt() != null
                    || block.containerSlotsBeforeSnbt() != null;
            if (!any) {
                report.onSkipped("no_snapshot");
                continue;
            }
            if (block.blockBeforeSnbt() != null) report.blocksRestored++;
            if (block.beBeforeSnbt() != null) report.blockEntitiesRestored++;
            if (block.containerSlotsBeforeSnbt() != null) report.containersRestored++;
            report.onApplied(block.type());
        }
        report.preparationIssues=prepared.preparationIssues();
        for(var entity:prepared.entityReplays()) {
            report.onApplied(entity.action());
            if(entity.after()==null)report.entitiesRemoved+=entity.tree().nodes().size();else report.entitiesRespawned+=entity.tree().nodes().size();
        }
        for (LogEntry entry : prepared.entityRemovals()) countPreparedEntry(entry, report, entityMarkers);
        for (LogEntry entry : prepared.deferred()) countPreparedEntry(entry, report, entityMarkers);
        return report;
    }

    /** Applies one small, atomic block group on the server thread. */
    public static RollbackReport applyPreparedBlockBatch(ServerLevel level, List<PreparedBlockSnapshot> blocks) {
        RollbackReport report = new RollbackReport();
        if (level == null || blocks == null || blocks.isEmpty()) return report;
        if (!level.getServer().isSameThread()) { report.onSkipped("wrong_thread"); return report; }
        java.util.LinkedHashMap<BlockPos, BlockSnapshot> snapshots = new java.util.LinkedHashMap<>();
        for (PreparedBlockSnapshot block : blocks) {
            if (block == null) continue;
            String invalid = validatePreparedBlock(level, block);
            if (invalid != null) {
                report.onSkipped(invalid);
                continue;
            }
            snapshots.put(block.pos(), new BlockSnapshot(block));
        }
        boolean restoring = CartAuditContext.restoring(true);
        try { applyConsolidatedBlocks(level, snapshots, report, true); }
        finally { CartAuditContext.restoring(restoring); }
        return report;
    }

    static String validatePreparedBlock(ServerLevel level, PreparedBlockSnapshot block) {
        if (!WorldSnapshotAccess.readable(level, block.pos())) return "chunk_unloaded";
        var target = block.stateTag() == null ? level.getBlockState(block.pos()) : strictReplayState(level, block.stateTag());
        if (target == null) return "invalid_block_snapshot";
        if (block.stateTag() != null && target.hasBlockEntity() && block.beTag() == null) return "missing_block_entity_snapshot";
        if (block.beBeforeSnbt() != null && block.beTag() == null) return "invalid_block_entity_nbt";
        if (block.beTag() != null) {
            var id = net.minecraft.resources.ResourceLocation.tryParse(block.beTag().getString("id"));
            var type = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
            if (!target.hasBlockEntity() || type == null || !type.isValid(target)) return "block_entity_type_mismatch";
        }
        if (block.containerSlotsBeforeSnbt() != null && !ContainerSlotSnapshot.isValid(block.slotsTag())) return "invalid_container_snapshot";
        if (block.expectedState() == null) return null;
        if (block.checkpoints().isEmpty()) {
            var expected = strictReplayState(level, block.expectedState());
            return expected != null && level.getBlockState(block.pos()).equals(expected) ? null : "replay_conflict_or_unsupported";
        }
        var current = currentCheckpoint(level, block.pos());
        if (sameCheckpoint(current, block.checkpoints().getLast())) return "replay_already_current";
        return block.checkpoints().stream().anyMatch(checkpoint -> sameCheckpoint(current, checkpoint)) ? null : "replay_world_conflict";
    }

    static ReplayCheckpoint currentCheckpoint(ServerLevel level, BlockPos pos) {
        var state = level.getBlockState(pos);
        var be = NbtSerde.snapshotBlockEntityAt(level, pos);
        if (state.hasBlockEntity() && be == null) throw new IllegalStateException("Текущий NBT блока не сохранён: " + level.dimension().location() + " " + pos);
        return new ReplayCheckpoint(net.minecraft.nbt.NbtUtils.writeBlockState(state), be, ContainerSlotSnapshot.snapshotTag(level, pos));
    }

    static BlockState strictReplayState(ServerLevel level, CompoundTag tag) {
        var state = NbtSerde.readBlockState(level, tag);
        return state != null && net.minecraft.nbt.NbtUtils.writeBlockState(state).equals(tag) ? state : null;
    }

    static boolean sameCheckpoint(ReplayCheckpoint a, ReplayCheckpoint b) {
        return a.state().equals(b.state()) && java.util.Objects.equals(a.be(), b.be())
                && (b.slots() == null || java.util.Objects.equals(a.slots(), b.slots()));
    }

    /** Applies a single non-block rollback operation on the server thread. */
    public static RollbackReport applyPreparedEntry(ServerLevel level, LogEntry entry) {
        RollbackReport report = new RollbackReport();
        if (level == null || entry == null) return report;
        if (!level.getServer().isSameThread()) { report.onSkipped("wrong_thread"); return report; }
        boolean restoring = CartAuditContext.restoring(true);
        try { applyRollback(level, entry, report, true); }
        finally { CartAuditContext.restoring(restoring); }
        return report;
    }

    private static RollbackReport rollbackByQueryPaged(ServerLevel level, LogQuery q, boolean apply) {
        PreparedRollback prepared = prepareByQueryPaged(LoggerRuntime.storage(level), q, () -> false);
        if (!apply) return previewPrepared(prepared);
        RollbackReport report = new RollbackReport();
        for (LogEntry entry : prepared.entityRemovals()) report.merge(applyPreparedEntry(level, entry));
        report.merge(applyPreparedBlockBatch(level, prepared.blocks()));
        for (LogEntry entry : prepared.deferred()) report.merge(applyPreparedEntry(level, entry));
        return report;
    }

    private static boolean modAuditOnly(LogEntry entry) {
        if (entry == null) return false;
        String source = java.util.Objects.toString(entry.source, "");
        return ("createdieselgenerators:chemical_sprayer_projectile".equals(entry.entityType)
                || entry.source != null && (source.startsWith("mod:corpse:") || source.startsWith("mod:accessories:")
                || source.startsWith("mod:ftbteams:") || source.startsWith("mod:openpartiesandclaims:")
                || source.startsWith("mod:createcobblestone:") || source.startsWith("mod:create_vibrant_vaults:")
                || source.startsWith("mod:create_enchantment_industry:") || source.startsWith("mod:createdieselgenerators:")));
    }
    private static void countPreparedEntry(LogEntry entry, RollbackReport report, java.util.function.Consumer<LogEntry> entityMarkers) {
        if (modAuditOnly(entry)) { report.onSkipped("not_rollbackable"); return; }
        if (entry == null || entry.type == null) {
            report.onSkipped("not_rollbackable");
            return;
        }
        switch (entry.type) {
            case ENTITY_DEATH, PLANE_REMOVE -> {
                if (entry.entityType == null || entry.entityNbt == null) {
                    report.onSkipped("no_entity_snapshot");
                    return;
                }
                CompoundTag entitySnapshot = entry.rollbackEntity();
                String invalid = NbtSerde.validateEntitySnapshot(entry.entityType, entry.entityUuid, entitySnapshot);
                if (invalid != null) {
                    report.onSkipped(invalid);
                    return;
                }
                if (NbtSerde.isCreateContraptionSnapshot(entry.entityType, entitySnapshot)) report.createStructuresRestored++;
                else report.entitiesRespawned++;
                report.onApplied(entry.type);
                if (entityMarkers != null) entityMarkers.accept(entry);
            }
            case ENTITY_SPAWN, PLANE_PLACE -> {
                if (entry.entityUuid == null) report.onSkipped("missing_uuid");
                else {
                    report.entitiesRemoved++;
                    report.onApplied(entry.type);
                }
            }
            case ITEM_DROP -> {
                if (entry.itemStackNbt == null || entry.itemStackNbt.isBlank()) report.onSkipped("invalid_stack");
                else {
                    report.itemsGivenOrSpawned++;
                    report.onApplied(entry.type);
                }
            }
            case ITEM_PICKUP, PLANE_PICKUP, ITEM_CRAFT, ITEM_SMELT -> {
                if (entry.itemStackNbt == null || entry.itemStackNbt.isBlank()) report.onSkipped("invalid_stack");
                else {
                    report.itemsRemovedFromInventory += Math.max(1, entry.count);
                    report.onApplied(entry.type);
                }
            }
            default -> report.onSkipped("not_rollbackable");
        }
    }

    private static PreparedRollback prepareByQueryPaged(LogStorage storage, LogQuery q, java.util.function.BooleanSupplier cancelled) {
        java.util.HashMap<BlockPos, BlockSnapshot> blockSnapshots = new java.util.HashMap<>();
        java.util.ArrayList<LogEntry> entityRemovals = new java.util.ArrayList<>();
        java.util.ArrayList<LogEntry> deferred = new java.util.ArrayList<>();
        long retainedBytes = 0L;
        long maxPreparedBytes;
        try {
            maxPreparedBytes = Math.max(64L, LoggerConfig.VALUES.rollbackMaxPreparedPayloadMiB.get()) * 1024L * 1024L;
        } catch (Throwable ignored) {
            maxPreparedBytes = 512L * 1024L * 1024L;
        }

        var seenIds = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        long beforeId = Long.MAX_VALUE;
        while (true) {
            if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            q.beforeId = beforeId;
            q.limit = PAGE;
            q.requireDetails = true;
            List<LogEntry> batch = storage.queryReverse(q);
            if (batch.isEmpty()) break;

            for (LogEntry e : batch) {
                if (e.id > 0 && !seenIds.add(e.id)) continue;
                retainedBytes += 24;
                if (CartAuditContext.id(e.source) != null) throw new IllegalStateException("В области есть вагонеточная конструкция Create. Используйте /log cart rollback <ID> <контрольная точка>, чтобы одновременно откатить конструкцию, груз и блоки");
                // A slot receipt describes a delta; without a before snapshot it must not
                // overwrite an actual checkpoint collected for the same block.
                if (isBlockRestoreType(e.type) && hasBlockBefore(e)) {
                    BlockPos pos = new BlockPos(e.x, e.y, e.z);
                    // Overwrite as we go backwards in time; the last value wins => oldest snapshot.
                    BlockSnapshot replacement = new BlockSnapshot(e.blockBefore, e.beBefore, e.containerSlotsBefore, e.type);
                    BlockSnapshot previous = blockSnapshots.put(pos, replacement);
                    retainedBytes += blockSnapshotWeight(replacement) - blockSnapshotWeight(previous);
                } else if (isEntityRemovalType(e.type)) {
                    // Undo spawned moving entities before stationary blocks are put back.
                    entityRemovals.add(e);
                    retainedBytes += PayloadSizeEstimator.estimate(e) + e.prepareForRollback();
                } else {
                    deferred.add(e);
                    retainedBytes += PayloadSizeEstimator.estimate(e) + e.prepareForRollback();
                }
                if (retainedBytes > maxPreparedBytes) {
                    throw new IllegalStateException("план превышает "
                            + (maxPreparedBytes / (1024L * 1024L)) + " MiB; сузьте область или период");
                }
            }

            long nextBeforeId = batch.get(batch.size() - 1).id;
            if (nextBeforeId <= 0L || nextBeforeId >= beforeId) break;
            beforeId = nextBeforeId;
            if (batch.size() < PAGE) break;
        }

        java.util.ArrayList<PreparedBlockSnapshot> blocks = new java.util.ArrayList<>(blockSnapshots.size());
        for (var entry : blockSnapshots.entrySet()) {
            BlockSnapshot snap = entry.getValue();
            PreparedBlockSnapshot parsed = new PreparedBlockSnapshot(entry.getKey(), snap.blockBeforeSnbt, snap.beBeforeSnbt,
                    snap.containerSlotsBeforeSnbt, snap.type);
            retainedBytes += PayloadSizeEstimator.estimateTags(parsed.stateTag, parsed.beTag, parsed.slotsTag);
            if (retainedBytes > maxPreparedBytes) throw new IllegalStateException("разобранный план превышает лимит памяти; сузьте область или период");
            blocks.add(parsed);
        }
        // Keep block batches chunk-local where possible. This reduces synchronous chunk loads and
        // preserves the existing per-batch transaction/verification semantics.
        blocks.sort(java.util.Comparator
                .comparingInt((PreparedBlockSnapshot b) -> b.pos().getX() >> 4)
                .thenComparingInt(b -> b.pos().getZ() >> 4)
                .thenComparingInt(b -> b.pos().getY())
                .thenComparingInt(b -> b.pos().getX())
                .thenComparingInt(b -> b.pos().getZ()));
        return new PreparedRollback(blocks, entityRemovals, deferred, Math.max(0L, retainedBytes));
    }

    private static long blockSnapshotWeight(BlockSnapshot snapshot) {
        if (snapshot == null) return 0L;
        return 160L + PayloadSizeEstimator.estimateStrings(snapshot.blockBeforeSnbt,
                snapshot.beBeforeSnbt, snapshot.containerSlotsBeforeSnbt);
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
                state = NbtSerde.readBlockState(level, snap.stateTag);
                if (state == null || !net.minecraft.nbt.NbtUtils.writeBlockState(state).equals(snap.stateTag)) {
                    report.onSkipped("invalid_block_snapshot"); continue;
                }
                if (state.hasBlockEntity() && snap.beTag == null) {
                    report.onSkipped("missing_block_entity_snapshot"); continue;
                }
            }
            if (snap.beBeforeSnbt != null && snap.beTag == null) {
                report.onSkipped("invalid_block_entity_nbt");
                continue;
            }
            // Refuse mismatched history before setBlock can remove a live inventory or emit drops.
            if (snap.beTag != null) {
                BlockState targetState = state == null ? level.getBlockState(pos) : state;
                boolean valid = targetState.hasBlockEntity();
                if (valid && snap.beTag.contains("id")) {
                    var id = net.minecraft.resources.ResourceLocation.tryParse(snap.beTag.getString("id"));
                    var type = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(id).orElse(null);
                    valid = type != null && type.isValid(targetState);
                }
                if (!valid) { report.onSkipped("block_entity_type_mismatch"); continue; }
            }
            if (snap.containerSlotsBeforeSnbt != null && !ContainerSlotSnapshot.isValid(snap.slotsTag)) {
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
            var current = level.getBlockState(block.pos);
            var be = NbtSerde.snapshotBlockEntity(level, level.getBlockEntity(block.pos));
            if (current.hasBlockEntity() && be == null) {
                for (int i = 0; i < prepared.size(); i++) report.onSkipped("live_backup_failed");
                return;
            }
            backups.add(new BlockBackup(block.pos, current, be, ContainerSlotSnapshot.snapshotTag(level, block.pos)));
        }

        String failure = null;
        java.util.HashSet<BlockPos> createTouched = new java.util.HashSet<>();
        for (PreparedBlock block : prepared) {
            if (block.state == null) continue;
            try {
                // Native container onRemove may drop its inventory. The detached backup owns
                // the old contents until commit/compensation; remove the old BE before replacement.
                if (!level.getBlockState(block.pos).equals(block.state)) level.removeBlockEntity(block.pos);
                level.setBlock(block.pos, block.state, 2 | 16);
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
                    if (!NbtSerde.readBlockEntity(level, block.pos, block.snapshot.beTag)) {
                        failure = "block_entity_restore_failed";
                        break;
                    }
                    var restoredNbt = NbtSerde.snapshotBlockEntityAt(level, block.pos);
                    var expectedNbt = block.snapshot.beTag.copy();
                    expectedNbt.putInt("x", block.pos.getX()); expectedNbt.putInt("y", block.pos.getY()); expectedNbt.putInt("z", block.pos.getZ());
                    if (!expectedNbt.equals(restoredNbt)) {
                        failure = "block_entity_verification_failed";
                        break;
                    }
                    if (looksLikeCreatePayload(block.snapshot.beBeforeSnbt)) createTouched.add(block.pos);
                }
                if (block.snapshot.containerSlotsBeforeSnbt != null) {
                    if (!ContainerSlotSnapshot.apply(level, block.pos, block.snapshot.slotsTag)) {
                        failure = "container_restore_failed";
                        break;
                    }
                    if (isCreateState(level.getBlockState(block.pos))) createTouched.add(block.pos);
                }
            }
        }

        if (failure != null) {
            if (!restoreBlockBackups(level, backups))
                throw new IllegalStateException("Не удалось полностью компенсировать порцию восстановления: " + failure);
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

    private static boolean restoreBlockBackups(ServerLevel level, java.util.List<BlockBackup> backups) {
        boolean restored = true;
        for (BlockBackup backup : backups) {
            try {
                if (!level.getBlockState(backup.pos).equals(backup.state)) level.removeBlockEntity(backup.pos);
                level.setBlock(backup.pos, backup.state, 2 | 16);
                if (!level.getBlockState(backup.pos).equals(backup.state)) restored = false;
            } catch (Throwable failure) {
                restored = false;
                com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] Block compensation failed at {}", backup.pos, failure);
            }
        }
        for (BlockBackup backup : backups) {
            try {
                if (backup.beSnbt != null && !NbtSerde.readBlockEntity(level, backup.pos, backup.beSnbt)) restored = false;
                if (backup.containerSnbt != null && !ContainerSlotSnapshot.apply(level, backup.pos, backup.containerSnbt)) restored = false;
                if (backup.beSnbt != null && !backup.beSnbt.equals(NbtSerde.snapshotBlockEntityAt(level, backup.pos))) restored = false;
            } catch (Throwable failure) {
                restored = false;
                com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error("[AvilixLogger] NBT compensation failed at {}", backup.pos, failure);
            }
        }
        return restored;
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

    private static boolean hasBlockBefore(LogEntry e){
        return e.blockBefore!=null&&!e.blockBefore.isBlank()||e.beBefore!=null&&!e.beBefore.isBlank()
            ||e.containerSlotsBefore!=null&&!e.containerSlotsBefore.isBlank();
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
        final CompoundTag stateTag, beTag, slotsTag;
        BlockSnapshot(String blockBeforeSnbt, String beBeforeSnbt, String containerSlotsBeforeSnbt, ActionType type) {
            this.blockBeforeSnbt = blockBeforeSnbt;
            this.beBeforeSnbt = beBeforeSnbt;
            this.containerSlotsBeforeSnbt = containerSlotsBeforeSnbt;
            this.type = type;
            this.stateTag = null; this.beTag = null; this.slotsTag = null;
        }
        BlockSnapshot(PreparedBlockSnapshot parsed) {
            this.blockBeforeSnbt = parsed.blockBeforeSnbt;
            this.beBeforeSnbt = parsed.beBeforeSnbt;
            this.containerSlotsBeforeSnbt = parsed.containerSlotsBeforeSnbt;
            this.type = parsed.type;
            this.stateTag = parsed.stateTag; this.beTag = parsed.beTag; this.slotsTag = parsed.slotsTag;
        }
    }

    private record PreparedBlock(BlockPos pos, BlockSnapshot snapshot, BlockState state) {}
    private record BlockBackup(BlockPos pos, BlockState state, CompoundTag beSnbt, CompoundTag containerSnbt) {}

    private static void applyRollback(ServerLevel level, LogEntry e, RollbackReport report, boolean apply) {
        if (modAuditOnly(e)) { report.onSkipped("not_rollbackable"); return; }
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
                        String invalid = NbtSerde.validateEntitySnapshot(e.entityType, e.entityUuid, e.rollbackEntity());
                        if (invalid != null) { report.onSkipped(invalid); return; }
                        if (!apply) {
                            if (NbtSerde.isCreateContraptionSnapshot(e.entityType, e.rollbackEntity())) report.createStructuresRestored++;
                            else report.entitiesRespawned++;
                            report.onApplied(e.type);
                            return;
                        }

                        boolean createSnapshot = NbtSerde.isCreateContraptionSnapshot(e.entityType, e.rollbackEntity());
                        NbtSerde.EntityRestoreResult result = createSnapshot
                                ? NbtSerde.restoreCreateContraptionAsBlocks(level, e.entityType, e.entityUuid, e.rollbackEntity())
                                : NbtSerde.restoreEntityFromSnapshot(level, e.entityType, e.entityUuid, e.rollbackEntity());
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
                    ItemStack st = NbtSerde.readItemStack(e.rollbackItem(), level.registryAccess());
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
                    ItemStack target = NbtSerde.readItemStack(e.rollbackItem(), level.registryAccess());
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
                    ItemStack out = NbtSerde.readItemStack(e.rollbackItem(), level.registryAccess());
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
