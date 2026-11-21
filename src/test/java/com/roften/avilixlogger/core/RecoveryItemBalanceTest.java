package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

final class RecoveryItemBalanceTest {
    private static final String DIM="minecraft:overworld";
    private static final BlockPos A=BlockPos.ZERO,B=new BlockPos(32,0,0);
    static void run() {
        verify(List.of(input(A,slots(2,0,7),slots(2,1,7))));
        verify(List.of(input(A,slots(1,0,7),slots(1,0,0)),input(B,slots(1,0,0),slots(1,0,7))));
        refused("replay_item_resource_graph_required",()->verify(List.of(input(A,slots(1,0,0),slots(1,0,7)))));
        refused("replay_item_resource_graph_required",()->verify(List.of(input(A,slots(1,0,7),slots(1,0,0)))));
        var named=slots(1,0,7);var components=new CompoundTag();components.putString("minecraft:custom_name","distinct");
        named.getList("Items",10).getCompound(0).put("components",components);
        refused("replay_item_resource_graph_required",()->verify(List.of(input(A,slots(1,0,7),named))));
        var shared=slots(2,0,7);shared.put("SlotSources",sources(A,B));
        var moved=slots(2,1,7);moved.put("SlotSources",sources(A,B));
        verify(List.of(input(A,shared,moved),input(B,shared.copy(),moved.copy())));
        refused("replay_resource_participant_outside_scene",()->verify(List.of(input(A,shared,moved))));
        var inconsistent=shared.copy();inconsistent.getList("Items",10).getCompound(0).putInt("count",6);
        refused("replay_resource_alias_conflict",()->verify(List.of(input(A,shared,moved),input(B,inconsistent,moved))));
        var malformed=shared.copy();malformed.getList("SlotSources",10).getCompound(1).putInt("FirstSlot",0);
        refused("replay_resource_alias_conflict",()->verify(List.of(input(A,malformed,moved),input(B,shared,moved))));
        var invalid=slots(1,0,7);invalid.getList("Items",10).getCompound(0).putDouble("count",7.5);
        refused("replay_invalid_resource_stack",()->verify(List.of(input(A,invalid,invalid))));
        var legacy=slots(1,0,7);var oldItem=legacy.getList("Items",10).getCompound(0);oldItem.remove("count");oldItem.putByte("Count",(byte)7);
        verify(List.of(input(A,legacy,slots(1,0,7))));
        refused("лимит памяти",()->RecoveryItemBalance.verify(List.of(input(A,slots(1,0,7),slots(1,0,7))),1,()->false));
        try {RecoveryItemBalance.verify(List.of(input(A,slots(1,0,7),slots(1,0,7))),1_000_000,()->true);throw new AssertionError("cancel ignored");}
        catch(java.util.concurrent.CancellationException expected) {}
        var before=slots(1,0,7);var copy=before.copy();verify(List.of(input(A,before,slots(1,0,7))));
        if(!before.equals(copy))throw new AssertionError("accounting mutated a checkpoint");
        profile();
        System.out.println("RECOVERY_ITEM_BALANCE_OK: closed cross-chunk transfers, rearrangement, mint/loss and component mismatch refusal, physical aliases counted once, external/contradictory aliases rejected, strict counts, memory bound and cancellation");
    }
    private static void verify(List<RecoveryItemBalance.Input> inputs){RecoveryItemBalance.verify(inputs,16*1024*1024,()->false);}
    private static void profile() {
        var inputs=new ArrayList<RecoveryItemBalance.Input>();
        for(int i=0;i<2048;i++)inputs.add(input(new BlockPos(i,64,0),slots(27,0,7),slots(27,1,7)));
        for(int i=0;i<3;i++)verify(inputs);
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        var allocation=bean instanceof com.sun.management.ThreadMXBean value&&value.isThreadAllocatedMemorySupported()?value:null;
        if(allocation!=null&&!allocation.isThreadAllocatedMemoryEnabled())allocation.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(),allocated=allocation==null?0:allocation.getThreadAllocatedBytes(thread);
        long start=System.nanoTime(),max=0;
        for(int i=0;i<5;i++){long one=System.nanoTime();verify(inputs);max=Math.max(max,System.nanoTime()-one);}
        long elapsed=System.nanoTime()-start;
        long bytes=allocation==null?-1:allocation.getThreadAllocatedBytes(thread)-allocated;
        System.out.printf(Locale.ROOT,"RECOVERY_ITEM_BALANCE_PROFILE blocks=2048 passes=5 average_ms=%.3f max_ms=%.3f allocated_bytes_per_pass=%d scope=detached_worker_only%n",elapsed/5e6,max/1e6,bytes<0?-1:bytes/5);
    }
    private static RecoveryItemBalance.Input input(BlockPos pos,CompoundTag before,CompoundTag after) {
        return new RecoveryItemBalance.Input(DIM,pos,new RollbackEngine.ReplayCheckpoint(new CompoundTag(),null,before),new RollbackEngine.ReplayCheckpoint(new CompoundTag(),null,after));
    }
    private static CompoundTag slots(int size,int slot,int count) {
        var tag=new CompoundTag();tag.putInt("Size",size);var items=new ListTag();
        if(count>0){var item=new CompoundTag();item.putString("id","minecraft:diamond");item.putInt("count",count);item.putInt("Slot",slot);items.add(item);}
        tag.put("Items",items);return tag;
    }
    private static ListTag sources(BlockPos... positions) {
        var result=new ListTag();int slot=0;
        for(var pos:positions){var source=new CompoundTag();source.putInt("FirstSlot",slot++);source.putInt("SlotCount",1);
            source.putString("Dimension",DIM);source.putInt("X",pos.getX());source.putInt("Y",pos.getY());source.putInt("Z",pos.getZ());result.add(source);}
        return result;
    }
    private static void refused(String reason,Runnable action) {
        try {action.run();throw new AssertionError("accepted: "+reason);}
        catch(IllegalStateException expected){if(!expected.getMessage().contains(reason))throw new AssertionError("wrong refusal: "+expected,expected);}
    }
}
