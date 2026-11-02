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
        System.out.println("STORAGE_ROLLBACK_EVIDENCE_OK: slot receipts preserve full checkpoints; absent restoration proof stays non-restorable");
    }
    private static RollbackEngine.PreparedRollback prepare(List<LogEntry> rows){
        LogStorage storage=new LogStorage(){
            public void append(LogEntry row){throw new UnsupportedOperationException();}
            public List<LogEntry> query(LogQuery q){return queryReverse(q);}
            public List<LogEntry> queryReverse(LogQuery q){return rows.stream().filter(e->q.beforeId<=0||e.id<q.beforeId).limit(q.limit).toList();}
            public void shutdown(){}
        };
        return RollbackEngine.prepareBoxRange(storage,"minecraft:overworld",BlockPos.ZERO,BlockPos.ZERO,0,Long.MAX_VALUE,null,null);
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
