package com.roften.avilixlogger.core;
import java.util.*;
import net.minecraft.core.BlockPos;

/** Per-slot receipts must not erase full rollback checkpoints at the same position. */
final class StorageRollbackEvidenceTest {
    static void run(){
        var checkpoint=AsyncPipelineTest.row("full checkpoint");checkpoint.id=20;checkpoint.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;checkpoint.dim="minecraft:overworld";
        checkpoint.blockBefore="{Name:'minecraft:chest'}";checkpoint.beBefore="{id:'minecraft:chest',Items:[]}";
        var receipt=AsyncPipelineTest.row("container slot 3; before=4; after=2");receipt.id=19;receipt.type=ActionType.CONTAINER_TAKE;receipt.dim=checkpoint.dim;receipt.count=2;receipt.beAfter="{StorageSlot:{Slot:3,BeforeCount:4,AfterCount:2}}";
        var plan=prepare(List.of(checkpoint,receipt));
        check(plan.blocks().size()==1&&checkpoint.beBefore.equals(plan.blocks().getFirst().beBeforeSnbt()),"audit-only delta erased restorable checkpoint");
        check(plan.deferred().size()==1&&plan.deferred().getFirst().id==receipt.id,"receipt turned into a fabricated block snapshot");
        plan=prepare(List.of(receipt));check(plan.blocks().isEmpty()&&plan.deferred().size()==1,"slot evidence was treated as a full restore snapshot");
        verifyReplay();
        System.out.println("STORAGE_ROLLBACK_EVIDENCE_OK: slot receipts preserve full checkpoints; absent restoration proof stays non-restorable");
    }
    private static void verifyReplay() {
        var first = AsyncPipelineTest.row("first replay");
        first.id = 1;
        first.actorName = "Steve";
        first.actorUuid = UUID.randomUUID();
        first.type = ActionType.BLOCK_PLACE;
        first.blockBefore = "{Name:'minecraft:air'}";
        first.blockAfter = "{Name:'minecraft:stone'}";
        var second = first.copyForQueue();
        second.id = 2;
        second.actorName = "Alex";
        second.blockBefore = first.blockAfter;
        second.blockAfter = "{Name:'minecraft:dirt'}";
        var gap = second.copyForQueue();
        gap.blockBefore = "{Name:'minecraft:grass_block'}";
        var scope = new RollbackPlanManager.Scope("minecraft:overworld", BlockPos.ZERO, BlockPos.ZERO);
        var owner = UUID.randomUUID();
        var plan = RollbackPlanManager.save(owner, 0, Long.MAX_VALUE, "Steve,Alex", null, List.of(scope), true);
        try {
            var replay = RollbackEngine.prepareBlockReplay(fixture(List.of(first, second)), plan, scope, () -> false);
            check(replay.blocks().size() == 1, "replay did not consolidate a matching chain");
            var block = replay.blocks().getFirst();
            check(block.expectedState().getString("Name").equals("minecraft:air")
                    && block.stateTag().getString("Name").equals("minecraft:dirt"), "replay reversed or reordered snapshots");
            var rejected = RollbackEngine.prepareBlockReplay(fixture(List.of(first, gap)), plan, scope, () -> false);
            check(rejected.blocks().isEmpty(), "replay accepted a broken chain");
        } finally {
            RollbackPlanManager.clear(owner);
        }
    }

    private static RollbackEngine.PreparedRollback prepare(List<LogEntry> rows){
        return RollbackEngine.prepareBoxRange(fixture(rows),"minecraft:overworld",BlockPos.ZERO,BlockPos.ZERO,0,Long.MAX_VALUE,null,null);
    }

    private static LogStorage fixture(List<LogEntry> rows) {
        return new LogStorage(){
            public void append(LogEntry row){throw new UnsupportedOperationException();}
            public List<LogEntry> query(LogQuery q) {
                check(q.actorNames.equals(List.of("Steve", "Alex")) && q.chronological, "replay lost player or time filter");
                return rows.stream().filter(e -> e.id > q.afterId).limit(q.limit).toList();
            }
            public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(e->q.beforeId<=0||e.id<q.beforeId).limit(q.limit).toList();}
            public void shutdown(){}
        };
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
