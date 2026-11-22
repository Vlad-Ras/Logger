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
        var spawn=AsyncPipelineTest.row("one spawned entity");spawn.id=22;spawn.type=ActionType.ENTITY_SPAWN;spawn.entityUuid=UUID.randomUUID();
        var duplicateEntities=prepare(List.of(spawn,spawn));
        check(duplicateEntities.entityRemovals().size()==1,"duplicate event ID repeated entity rollback");
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
            var cycle=first.copyForQueue();cycle.id=3;cycle.ts=second.ts+1;cycle.blockBefore=second.blockAfter;cycle.blockAfter=first.blockBefore;
            var cycled=RollbackEngine.prepareBlockReplay(fixture(List.of(first,second,cycle)),plan,scope,()->false);
            check(cycled.blocks().size()==1&&cycled.blocks().getFirst().checkpoints().size()==4,"closed history cycle discarded a possible persisted intermediate state");
            check(block.expectedState().getString("Name").equals("minecraft:air")
                    && block.stateTag().getString("Name").equals("minecraft:dirt"), "replay reversed or reordered snapshots");
            var repeated = RollbackEngine.prepareBlockReplay(fixture(List.of(first, first, second, second)), plan, scope, () -> false);
            check(repeated.blocks().size()==1 && repeated.preparationSkips().isEmpty()
                    && repeated.blocks().getFirst().checkpoints().size()==3, "duplicate event ID broke or repeated the recovery chain");
            var rejected = RollbackEngine.prepareBlockReplay(fixture(List.of(first, gap)), plan, scope, () -> false);
            check(rejected.blocks().isEmpty() && rejected.preparationSkips().get("replay_history_gap") == 1,
                    "replay accepted a broken chain or hid its reason");
            var outsider = second.copyForQueue(); outsider.actorName = "Other";
            var filtered = RollbackEngine.prepareBlockReplay(fixture(List.of(first, outsider)), plan, scope, () -> false);
            check(filtered.blocks().isEmpty() && filtered.preparationSkips().containsKey("replay_filtered_chain"),
                    "replay hid another actor's mutation");
            var chest = first.copyForQueue(); chest.blockAfter = "{Name:'minecraft:chest'}";
            chest.beAfter = "{id:'minecraft:chest',x:0,y:0,z:0,Items:[]}";
            var full = RollbackEngine.prepareBlockReplay(fixture(List.of(chest)), plan, scope, () -> false);
            check(full.blocks().size() == 1 && full.blocks().getFirst().beTag().contains("Items")
                    && full.blocks().getFirst().checkpoints().size() == 2, "replay lost block entity checkpoints");
            var nbt = chest.copyForQueue(); nbt.id = 3; nbt.type = ActionType.BLOCK_ENTITY_NBT_CHANGE;
            nbt.blockBefore = null; nbt.beBefore = chest.beAfter; nbt.beAfter = chest.beAfter;
            nbt.containerSlotsBefore = "{Size:27,Items:[]}"; nbt.containerSlotsAfter = nbt.containerSlotsBefore;
            var receipt = chest.copyForQueue(); receipt.id = 2; receipt.type = ActionType.CONTAINER_PUT;
            receipt.blockBefore = null; receipt.beBefore = null; receipt.beAfter = "{StorageSlot:{Slot:1}}";
            var paired = RollbackEngine.prepareBlockReplay(fixture(List.of(chest, receipt, nbt)), plan, scope, () -> false);
            check(paired.blocks().size() == 1 && paired.preparationSkips().isEmpty(), "paired menu receipt erased full NBT recovery");
            var unpaired = RollbackEngine.prepareBlockReplay(fixture(List.of(chest, receipt)), plan, scope, () -> false);
            check(unpaired.blocks().isEmpty() && unpaired.preparationSkips().containsKey("replay_unpaired_container_delta"),
                    "unpaired slot receipt allowed stale inventory recovery");
            chest.beAfter = "{StorageSlot:{Slot:1}}";
            var delta = RollbackEngine.prepareBlockReplay(fixture(List.of(chest)), plan, scope, () -> false);
            check(delta.blocks().isEmpty() && delta.preparationSkips().containsKey("replay_incomplete_snapshot"),
                    "replay used a delta envelope as full NBT");
            try {
                RollbackEngine.prepareBlockReplay(fixture(List.of(first)), plan, scope, () -> true);
                throw new AssertionError("cancelled replay continued");
            } catch (java.util.concurrent.CancellationException expected) {}
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
                check(q.actorNames.isEmpty() && q.actorName == null && q.chronological, "replay must inspect all actors in chronological order");
                return rows.stream().filter(e -> e.id > q.afterId).limit(q.limit).toList();
            }
            public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(e->q.beforeId<=0||e.id<q.beforeId).limit(q.limit).toList();}
            public void shutdown(){}
        };
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
