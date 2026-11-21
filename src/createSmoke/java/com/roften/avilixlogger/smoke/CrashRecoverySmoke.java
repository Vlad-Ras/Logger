package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import java.util.*;

/** Native fixtures exercise the production engine; no AuthCore bypass or test code in the JAR. */
final class CrashRecoverySmoke {
    private static final BlockPos POS = new BlockPos(96, 180, 96);
    static void run(ServerLevel level) throws Exception {
        long started = System.nanoTime();
        long heapBefore = usedHeap();
        boolean restoring = CartAuditContext.restoring(true);
        var previous = level.getBlockState(POS);
        var previousNbt = NbtSerde.snapshotBlockEntityAt(level, POS);
        try {
            level.getChunk(POS);
            for (var state : List.of(Blocks.OAK_FENCE.defaultBlockState(), Blocks.OAK_STAIRS.defaultBlockState(),
                    Blocks.WATER.defaultBlockState(), com.simibubi.create.AllBlocks.ANDESITE_CASING.getDefaultState())) {
                reset(level, Blocks.AIR.defaultBlockState());
                var row = row(); row.blockBefore = NbtSerde.writeBlockState(Blocks.AIR.defaultBlockState());
                row.blockAfter = NbtSerde.writeBlockState(state);
                var prepared = prepare(List.of(row));
                var result = RollbackEngine.applyPreparedBlockBatch(level, prepared.blocks());
                check(result.applied == 1 && level.getBlockState(POS).equals(state), "non-simple/mod/fluid block was refused: " + state);
                check(RollbackEngine.applyPreparedBlockBatch(level, prepared.blocks()).skippedReasons.containsKey("replay_already_current"),
                        "recovery reapplied an already restored block");
            }
            reset(level, Blocks.CHEST.defaultBlockState());
            var chest = (ChestBlockEntity)level.getBlockEntity(POS);
            chest.setItem(0, new ItemStack(Items.DIAMOND, 7));
            var row = row(); row.blockBefore = NbtSerde.writeBlockState(Blocks.AIR.defaultBlockState());
            row.blockAfter = NbtSerde.writeBlockState(level.getBlockState(POS));
            row.beAfter = NbtSerde.writeBlockEntity(level, chest);
            row.containerSlotsAfter = ContainerSlotSnapshot.snapshot(level, POS);
            var full = prepare(List.of(row));
            reset(level, Blocks.AIR.defaultBlockState());
            int drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(3)).size();
            var result = RollbackEngine.applyPreparedBlockBatch(level, full.blocks());
            check(result.applied == 1 && ((ChestBlockEntity)level.getBlockEntity(POS)).getItem(0).getCount() == 7,
                    "container/NBT contents were lost: " + result.skippedReasons);
            result = RollbackEngine.applyPreparedBlockBatch(level, full.blocks());
            check(result.applied == 0 && result.skippedReasons.containsKey("replay_already_current"), "container replay duplicated contents");
            ((ChestBlockEntity)level.getBlockEntity(POS)).setItem(0, new ItemStack(Items.DIAMOND, 6));
            result = RollbackEngine.applyPreparedBlockBatch(level, full.blocks());
            check(result.applied == 0 && ((ChestBlockEntity)level.getBlockEntity(POS)).getItem(0).getCount() == 6,
                    "changed live inventory was overwritten");
            var breakRow = row(); breakRow.type = ActionType.BLOCK_BREAK;
            breakRow.blockBefore = row.blockAfter; breakRow.beBefore = NbtSerde.writeBlockEntity(level, level.getBlockEntity(POS));
            breakRow.containerSlotsBefore = ContainerSlotSnapshot.snapshot(level, POS);
            breakRow.blockAfter = row.blockBefore;
            result = RollbackEngine.applyPreparedBlockBatch(level, prepare(List.of(breakRow)).blocks());
            check(result.applied == 1 && level.getBlockState(POS).isAir(), "recorded removal not replayed");
            check(level.getEntitiesOfClass(ItemEntity.class, new AABB(POS).inflate(3)).size() == drops, "container replacement emitted duplicate drops");

            var incomplete = row(); incomplete.blockBefore = row.blockBefore; incomplete.blockAfter = row.blockAfter;
            result = RollbackEngine.applyPreparedBlockBatch(level, prepare(List.of(incomplete)).blocks());
            check(result.applied == 0 && level.getBlockState(POS).isAir(), "container was restored without full NBT");
            var unknown = row(); unknown.blockBefore = row.blockBefore; unknown.blockAfter = "{Name:'absent:block'}";
            result = RollbackEngine.applyPreparedBlockBatch(level, prepare(List.of(unknown)).blocks());
            check(result.applied == 0 && level.getBlockState(POS).isAir(), "unknown registry entry became air silently");

            // A persisted intermediate checkpoint is valid even when it differs from the first state.
            var first = row(); first.blockBefore = row.blockBefore; first.blockAfter = NbtSerde.writeBlockState(Blocks.STONE.defaultBlockState());
            var next = row(); next.id = 2; next.ts++; next.blockBefore = first.blockAfter;
            next.blockAfter = NbtSerde.writeBlockState(Blocks.DIRT.defaultBlockState());
            reset(level, Blocks.STONE.defaultBlockState());
            result = RollbackEngine.applyPreparedBlockBatch(level, prepare(List.of(first, next)).blocks());
            check(result.applied == 1 && level.getBlockState(POS).is(Blocks.DIRT), "intermediate saved checkpoint rejected");

            boolean disabled = level.noSave;
            try { level.noSave = true; RecoveryCheckpoint.flush(level.getServer()); check(level.noSave, "checkpoint enabled previously disabled saves"); }
            finally { level.noSave = disabled; }
            tickJob(level, first, true);
            tickJob(level, first, false);
            RecoverySceneSmoke.run(level);
            System.out.printf(Locale.ROOT, "CRASH_RECOVERY_NATIVE_OK blocks=fence+stairs+water+create container=NBT+slots+idempotent+conflict+no-drops history=intermediate+unknown+incomplete checkpoint=forced+preserved tick=bounded elapsed_ms=%.2f heap_delta_mib=%.2f%n",
                    (System.nanoTime()-started)/1e6, (usedHeap()-heapBefore)/1048576.0);
        } finally {
            reset(level, previous);
            if (previousNbt != null) NbtSerde.readBlockEntity(level, POS, previousNbt);
            CartAuditContext.restoring(restoring);
        }
    }
    private static void tickJob(ServerLevel level, LogEntry row,boolean replay) throws Exception {
        reset(level, replay?Blocks.AIR.defaultBlockState():Blocks.STONE.defaultBlockState());
        var owner = UUID.randomUUID();
        var scope = new RollbackPlanManager.Scope(level.dimension().location().toString(), POS, POS);
        var plan = RollbackPlanManager.save(owner, 1, Long.MAX_VALUE, null, null, List.of(scope), replay);
        try {
            var scopeType = Class.forName("com.roften.avilixlogger.core.RollbackCoordinator$PreparedScope");
            var scopeConstructor = scopeType.getDeclaredConstructors()[0]; scopeConstructor.setAccessible(true);
            var prepared=replay?prepare(List.of(row)):RollbackEngine.prepareBoxRange(new History(List.of(row)),scope.dimension(),POS,POS,1,Long.MAX_VALUE,null,null);
            var preparedScope = scopeConstructor.newInstance(scope.dimension(), prepared);
            var type = Class.forName("com.roften.avilixlogger.core.RollbackCoordinator$ActiveJob");
            var constructor = type.getDeclaredConstructors()[0]; constructor.setAccessible(true);
            java.util.function.Consumer<RollbackReport> completed = report -> check(report.applied == 1, "job failed to apply its block");
            java.util.function.Consumer<String> failure = message -> { throw new AssertionError(message); };
            var job = constructor.newInstance(owner, plan, List.of(preparedScope), completed, failure);
            var tick = type.getDeclaredMethod("tick", net.minecraft.server.MinecraftServer.class); tick.setAccessible(true);
            boolean finished = false;
            for (int i = 0; i < 5000 && !finished; i++) {
                finished = (boolean)tick.invoke(job, level.getServer());
                if(!finished)java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(1));
            }
            check(finished && (replay?level.getBlockState(POS).is(Blocks.STONE):level.getBlockState(POS).isAir()), "recovery/ordinary rollback job did not make progress");
            for(String field:List.of("checkpointStarted","checkpointFinished")) {
                var value=type.getDeclaredField(field);value.setAccessible(true);check((boolean)value.get(job),"recovery/ordinary rollback missed "+field);
            }
        } finally { RollbackPlanManager.clear(owner); }
    }
    private static RollbackEngine.PreparedRollback prepare(List<LogEntry> rows) {
        var owner=UUID.randomUUID();
        var scope = new RollbackPlanManager.Scope("minecraft:overworld", POS, POS);
        var plan = RollbackPlanManager.save(owner, 1, Long.MAX_VALUE, null, null, List.of(scope), true);
        try { return RollbackEngine.prepareBlockReplay(new History(rows), plan, scope, () -> false); }
        finally { RollbackPlanManager.clear(owner); }
    }
    private static LogEntry row() {
        var row = new LogEntry(); row.id = 1; row.ts = 1000; row.type = ActionType.BLOCK_PLACE;
        row.dim = "minecraft:overworld"; row.x=POS.getX(); row.y=POS.getY(); row.z=POS.getZ();
        row.actorName="Steve"; row.actorUuid=UUID.randomUUID(); return row;
    }
    private record History(List<LogEntry> rows) implements LogStorage {
        public void append(LogEntry row) { throw new UnsupportedOperationException(); }
        public List<LogEntry> query(LogQuery query) { return rows.stream().filter(row -> row.id > query.afterId).limit(query.limit).toList(); }
        public List<LogEntry> queryReverse(LogQuery query) { return rows.stream().filter(row->row.ts>=query.sinceTs&&row.ts<=query.untilTs&&row.id<query.beforeId).sorted(Comparator.comparingLong((LogEntry row)->row.id).reversed()).limit(query.limit).toList(); }
        public void shutdown() {}
    }
    private static void reset(ServerLevel level, net.minecraft.world.level.block.state.BlockState state) {
        level.removeBlockEntity(POS); level.setBlock(POS, Blocks.AIR.defaultBlockState(), 2 | 16); level.setBlock(POS, state, 2 | 16);
    }
    private static long usedHeap() { return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory(); }
    private static void check(boolean condition,String message) { if (!condition) throw new AssertionError(message); }
}
