package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real ticks and durable commits, not a tight synthetic application loop. No AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class RecoveryBenchmarkSmoke {
    private static Fixture active;
    static void start(ServerLevel level){active=new Fixture(level);}
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        if(active==null)return;
        try {if(active.tick()){active=null;event.getServer().halt(false);}}
        catch(Throwable problem){problem.printStackTrace();System.out.println("RECOVERY_BENCHMARK_FAILED");active=null;event.getServer().halt(false);}
    }
    private static final class Fixture {
        final ServerLevel level;
        final int[] sizes={100,2048};
        RecoveryScene scene;
        List<BlockPos> positions;
        int index,ticks;
        boolean cleanup,resident;
        int unloadedChunks;
        long started,initialSaveNanos,maxStepNanos,peakHeap,startHeap;
        Fixture(ServerLevel level){this.level=level;}
        boolean tick() {
            long stepStart=System.nanoTime();
            if(scene==null) {
                if(index==sizes.length){System.out.println("RECOVERY_BENCHMARK_NATIVE_OK: actual ticks, 100/2048 blocks, initial/resident passes, world checkpoints and durable journals, final states verified");return true;}
                positions=new ArrayList<>();
                for(int n=0;n<sizes[index];n++)positions.add(new BlockPos(512+n%32,312,512+n/32));
                unloadedChunks=(int)positions.stream().map(net.minecraft.world.level.ChunkPos::new).distinct()
                        .filter(pos->level.getChunkSource().getChunkNow(pos.x,pos.z)==null).count();
                startHeap=heap();peakHeap=startHeap;started=System.nanoTime();ticks=0;maxStepNanos=0;
                RecoveryCheckpoint.flush(level.getServer());initialSaveNanos=System.nanoTime()-started;
                scene=create(false);
            }
            scene.step(LoggerConfig.VALUES.recoveryOperationsPerTick.get(),System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(LoggerConfig.VALUES.recoveryMaxMillisPerTick.get()),
                    LoggerConfig.VALUES.recoveryChunkLoadsPerTick.get());
            if(scene.aborted()||scene.quarantined())throw new AssertionError("benchmark scene failed: "+scene.failure());
            ticks++;peakHeap=Math.max(peakHeap,heap());
            if(scene.ready()) {
                scene.commit();
                maxStepNanos=Math.max(maxStepNanos,System.nanoTime()-stepStart);
                for(var pos:positions)if(cleanup?!level.getBlockState(pos).isAir():!level.getBlockState(pos).is(Blocks.STONE))throw new AssertionError("benchmark world differs at "+pos);
                if(!cleanup) {
                    System.out.printf(Locale.ROOT,"RECOVERY_SCENE_PROFILE blocks=%d pass=%s unloaded_target_chunks=%d ticks=%d elapsed_ms=%.3f initial_save_ms=%.3f max_callback_ms=%.3f heap_sample_delta_mib=%.3f heap_sample_peak_mib=%.3f%n",
                            sizes[index],resident?"resident":"initial",unloadedChunks,ticks,(System.nanoTime()-started)/1e6,initialSaveNanos/1e6,maxStepNanos/1e6,(peakHeap-startHeap)/1048576.0,peakHeap/1048576.0);
                    cleanup=true;scene=create(true);
                } else {cleanup=false;scene=null;positions=null;if(resident){index++;resident=false;}else resident=true;}
            } else maxStepNanos=Math.max(maxStepNanos,System.nanoTime()-stepStart);
            return false;
        }
        RecoveryScene create(boolean undo) {
            var air=NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState());var stone=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
            var before=new RollbackEngine.ReplayCheckpoint(undo?stone:air,null,null);var after=new RollbackEngine.ReplayCheckpoint(undo?air:stone,null,null);
            var targets=new ArrayList<RecoveryScene.Target>();
            for(var pos:positions)targets.add(new RecoveryScene.Target(level,new RollbackEngine.PreparedBlockSnapshot(pos,after.state().toString(),null,null,ActionType.BLOCK_PLACE,
                    after.state(),null,null,before.state(),List.of(before,after))));
            return new RecoveryScene(targets,64L*1024*1024,128);
        }
        long heap(){return Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory();}
    }
}
