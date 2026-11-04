package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import java.lang.management.ManagementFactory;
import java.nio.file.*;

/** Data-inspector cost on actual native storage. Never authorizes production capture. */
public final class CreateAutomationCostSmoke {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static volatile Object sink;
    public static void run(ServerLevel world) throws Exception {
        var sourcePos=world.getSharedSpawnPos().offset(820,96,820);var target=sourcePos.offset(2,0,0);
        world.getChunkAt(sourcePos);world.getChunkAt(target);
        world.setBlock(sourcePos,AllBlocks.CHUTE.getDefaultState(),3);world.setBlock(target,Blocks.CHEST.defaultBlockState(),3);
        var source=world.getBlockEntity(sourcePos);var inventory=new InvWrapper((net.minecraft.world.Container)world.getBlockEntity(target));
        inventory.setStackInSlot(0,new ItemStack(Items.STONE));
        try {
            // Positive control: this is the exact native descriptor used by the former
            // eager branch, including real container identity and registry block state.
            HotPathMetrics.start();
            for(int i=0;i<100;i++)check(CartAutomationStorage.describe(world,target,"none","item",0,inventory)!=null,"descriptor control unavailable");
            check(HotPathMetrics.automationDescriptors()==100,"descriptor probe was inactive");HotPathMetrics.stop();
            for(int i=0;i<1000;i++)transfer(world,source,target,inventory,i);
            var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            bean.setThreadAllocatedMemoryEnabled(true);long thread=Thread.currentThread().threadId();
            long allocated=bean.getThreadAllocatedBytes(thread),cpu=bean.getCurrentThreadCpuTime(),wall=System.nanoTime();
            HotPathMetrics.start();
            for(int i=0;i<5000;i++)transfer(world,source,target,inventory,i);
            long descriptors=HotPathMetrics.automationDescriptors(),snapshots=HotPathMetrics.snapshotCount();HotPathMetrics.stop();
            allocated=bean.getThreadAllocatedBytes(thread)-allocated;cpu=bean.getCurrentThreadCpuTime()-cpu;wall=System.nanoTime()-wall;
            check(descriptors==0&&snapshots==0,"unrelated native logistics serialized audit metadata");
            String json="{\"scope\":\"native-standalone-transfer-data-inspector\",\"operations\":5000,\"warmup\":1000,\"descriptor_control\":100,\"descriptor_builds\":"+descriptors+",\"nbt_snapshot_calls\":"+snapshots+",\"allocated_bytes\":"+allocated+",\"cpu_ns\":"+cpu+",\"wall_ns\":"+wall+",\"production_authcore\":false,\"full_modpack\":false}";
            Files.writeString(Path.of("avilixlogger-2.1.29-automation-cost.json"),json);
            System.out.println("CREATE_AUTOMATION_COST_NATIVE_OK: "+json);
        } finally {HotPathMetrics.stop();sink=null;world.removeBlock(sourcePos,false);world.removeBlock(target,false);}
    }
    private static void transfer(ServerLevel world,net.minecraft.world.level.block.entity.BlockEntity source,BlockPos target,InvWrapper inventory,int i){
        var scope=CartAutomationAudit.captureChanges(source);
        try {
            CartAutomationHooks.exposed(world,target,null,inventory);
            var change=CartAutomationHooks.item(inventory,0,false);
            try{inventory.setStackInSlot(0,new ItemStack((i&1)==0?Items.DIRT:Items.STONE));}
            finally{if(change!=null)change.finish(true);}
            var rows=scope.snapshotRows(true);check(rows.isEmpty(),"standalone inspector invented a cart transfer");sink=rows;
        } finally {scope.finish(true);}
    }
}
