package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Dev-only destructive fixture in its own world. halts the JVM without any shutdown hooks. */
@EventBusSubscriber(modid="avilixlogger")
public final class RecoveryCrashProcessSmoke {
    private static final BlockPos A=new BlockPos(111,190,96),B=A.east();
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        String mode=System.getProperty("avilixlogger.recoveryCrash");if(mode==null)return;
        var server=event.getServer();var level=server.overworld();
        if(mode.equals("auth-api")){AuthCoreNativeSmoke.run(server);server.halt(false);return;}
        if(mode.equals("benchmark")){RecoveryBenchmarkSmoke.start(level);return;}
        if(mode.equals("scene")){CrashRecoverySmoke.run(level);server.halt(false);return;}
        if(mode.equals("entities")){EntityRecoveryNativeSmoke.run(level);server.halt(false);return;}
        if(mode.startsWith("entity-")){EntityRecoveryProcessSmoke.run(level,mode);return;}
        for(var pos:List.of(A,B)){level.getChunk(pos);for(var side:net.minecraft.core.Direction.values())level.getChunk(pos.relative(side));}
        if(mode.equals("verify")) {
            checkBefore(level);
            Path dir=server.getWorldPath(LevelResource.ROOT).resolve("data/avilixlogger-recovery");
            try(var paths=Files.list(dir)){check(paths.noneMatch(path->path.toString().endsWith(".scene")),"completed startup compensation kept undo journal");}
            System.out.println("RECOVERY_RESTART_NATIVE_OK: separate process restored full chest/slots and both chunks before gameplay; no item drops; undo record retired");
            server.halt(false);return;
        }
        if(!mode.equals("states")&&!mode.equals("data"))throw new AssertionError("Unknown crash phase: "+mode);
        boolean previous=CartAuditContext.restoring(true);
        try {
            // The second crash process must see the first interrupted scene already compensated.
            Path probe=server.getWorldPath(LevelResource.ROOT).resolve("recovery-crash-probe.txt");
            if(Files.exists(probe))checkBefore(level);
            reset(level,A,Blocks.BARREL.defaultBlockState());
            ((RandomizableContainerBlockEntity)level.getBlockEntity(A)).setItem(0,new ItemStack(Items.DIAMOND,7));
            var after=checkpoint(level,A);
            reset(level,A,Blocks.CHEST.defaultBlockState());
            ((RandomizableContainerBlockEntity)level.getBlockEntity(A)).setItem(0,new ItemStack(Items.DIAMOND,7));
            ((RandomizableContainerBlockEntity)level.getBlockEntity(A)).setItem(1,new ItemStack(Items.GOLD_INGOT,9));
            var before=checkpoint(level,A);
            reset(level,B,Blocks.BARREL.defaultBlockState());
            ((RandomizableContainerBlockEntity)level.getBlockEntity(B)).setItem(0,new ItemStack(Items.GOLD_INGOT,9));
            var newB=checkpoint(level,B);
            reset(level,B,Blocks.IRON_BLOCK.defaultBlockState());var oldB=checkpoint(level,B);
            RecoveryCheckpoint.flush(server);
            var scene=new RecoveryScene(List.of(new RecoveryScene.Target(level,target(A,before,after)),new RecoveryScene.Target(level,target(B,oldB,newB))),64L*1024*1024,128);
            var phase=mode.equals("states")?RecoveryScene.Phase.DATA:RecoveryScene.Phase.VERIFY;
            for(int i=0;i<10000&&scene.phase()!=phase&&!scene.aborted();i++) {
                scene.step(1,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),16);
                if(scene.phase()==RecoveryScene.Phase.WRITE_JOURNAL||scene.phase()==RecoveryScene.Phase.RESOURCE_CHECK)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            check(scene.phase()==phase,"could not stage crash: "+scene.phase()+" "+scene.failure());
            check(level.getBlockState(A).is(Blocks.BARREL)&&level.getBlockState(B).is(Blocks.BARREL),"both chunks were not modified before crash");
            RecoveryCheckpoint.flush(server); // Intentionally persist a partial/finished but uncommitted scene.
            Files.writeString(probe,mode,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
            System.out.println("RECOVERY_FORCED_CRASH_READY phase="+mode+" first_items="+((RandomizableContainerBlockEntity)level.getBlockEntity(A)).getItem(0).getCount()
                    +" second_items="+((RandomizableContainerBlockEntity)level.getBlockEntity(B)).getItem(0).getCount());
            System.out.flush();
            Runtime.getRuntime().halt(73);
        } finally {CartAuditContext.restoring(previous);}
    }
    private static void checkBefore(ServerLevel level) {
        check(level.getBlockState(A).is(Blocks.CHEST)&&level.getBlockState(B).is(Blocks.IRON_BLOCK),"startup left mixed chunk states");
        var chest=(RandomizableContainerBlockEntity)level.getBlockEntity(A);
        check(chest.getItem(0).is(Items.DIAMOND)&&chest.getItem(0).getCount()==7,"startup lost/duplicated full chest NBT");
        check(chest.getItem(1).is(Items.GOLD_INGOT)&&chest.getItem(1).getCount()==9,"startup lost linked transfer source inventory");
        check(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(A).inflate(4)).isEmpty(),"startup compensation emitted duplicate drops");
    }
    private static RollbackEngine.ReplayCheckpoint checkpoint(ServerLevel level,BlockPos pos) {
        return new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(level.getBlockState(pos)),NbtSerde.snapshotBlockEntityAt(level,pos),ContainerSlotSnapshot.snapshotTag(level,pos));
    }
    private static RollbackEngine.PreparedBlockSnapshot target(BlockPos pos,RollbackEngine.ReplayCheckpoint before,RollbackEngine.ReplayCheckpoint after) {
        return new RollbackEngine.PreparedBlockSnapshot(pos,after.state().toString(),NbtSerde.toSnbt(after.be()),NbtSerde.toSnbt(after.slots()),ActionType.BLOCK_PLACE,
                after.state(),after.be(),after.slots(),before.state(),List.of(before,after));
    }
    private static void reset(ServerLevel level,BlockPos pos,net.minecraft.world.level.block.state.BlockState state){level.removeBlockEntity(pos);level.setBlock(pos,state,2|16|32|64);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
