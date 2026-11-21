package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Multiple ticks/batches must never leave a partially recovered build on rejection/cancellation. */
final class RecoverySceneSmoke {
    private static final BlockPos A=new BlockPos(111,184,96), B=A.east();
    static void run(ServerLevel level) {
        var positions=List.of(A,B,A.above(),A.below());
        var original=new HashMap<BlockPos,RollbackEngine.ReplayCheckpoint>();
        for (var pos:positions) {
            level.getChunk(pos);
            for (var direction:Direction.values()) level.getChunk(pos.relative(direction));
            original.put(pos,new RollbackEngine.ReplayCheckpoint(net.minecraft.nbt.NbtUtils.writeBlockState(level.getBlockState(pos)),NbtSerde.snapshotBlockEntityAt(level,pos),ContainerSlotSnapshot.snapshotTag(level,pos)));
        }
        try {
            reset(level,A,Blocks.AIR.defaultBlockState()); reset(level,B,Blocks.GOLD_BLOCK.defaultBlockState());
            var scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),target(B,Blocks.DIRT.defaultBlockState())));
            drain(scene); check(scene.aborted(),"late preflight conflict accepted");
            check(level.getBlockState(A).isAir()&&level.getBlockState(B).is(Blocks.GOLD_BLOCK),"preflight left partial world changes");

            reset(level,B,Blocks.AIR.defaultBlockState());
            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),target(B,Blocks.DIRT.defaultBlockState()))).preflightOnly();
            drain(scene);check(scene.ready()&&!scene.changedWorld(),"live preview failed or changed the world: "+scene.failure());
            try {scene.commit();throw new AssertionError("preview accepted a world-changing commit");}
            catch(IllegalStateException expected) {}
            var preview=scene.completePreflight();
            check(preview.livePreviewVerified&&preview.applied==2&&level.getBlockState(A).isAir()&&level.getBlockState(B).isAir()
                    &&!CartAuditContext.rollbackActive,"live preview mutated blocks or retained locks");
            System.out.println("RECOVERY_LIVE_PREFLIGHT_NATIVE_OK: exact world validation and counts, no writes or commit, reservations released before confirmation");
            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),target(B,Blocks.DIRT.defaultBlockState())));
            advanceTo(scene,RecoveryScene.Phase.DATA);
            check(level.getBlockState(A).is(Blocks.STONE)&&level.getBlockState(B).is(Blocks.DIRT),"scene did not stage both chunk states");
            boolean previous=CartAuditContext.restoring(false);
            try { check(!level.setBlock(A,Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved block accepted an external write between batches"); }
            finally { CartAuditContext.restoring(previous); }
            scene.cancel("fixture cancellation after both chunks");drain(scene);
            check(scene.aborted()&&level.getBlockState(A).isAir()&&level.getBlockState(B).isAir(),"cancel did not compensate earlier batches");
            check(!CartAuditContext.rollbackActive,"cancel leaked global locks");

            simulationLocks(level);
            journalWaitCancellation(level);
            staleStorageHandles(level);
            reset(level,B,Blocks.CHEST.defaultBlockState());
            var chestNbt=NbtSerde.snapshotBlockEntityAt(level,B);
            reset(level,B,Blocks.AIR.defaultBlockState());
            var badSlots=new CompoundTag();badSlots.putInt("Size",99);badSlots.put("Items",new net.minecraft.nbt.ListTag());
            var chest=target(B,Blocks.CHEST.defaultBlockState(),chestNbt,badSlots);
            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),chest));
            drain(scene);
            check(scene.aborted()&&level.getBlockState(A).isAir()&&level.getBlockState(B).isAir(),"late slot failure left earlier chunk restored");

            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),target(B,Blocks.DIRT.defaultBlockState())));
            drain(scene);check(scene.ready(),"valid two-chunk scene failed: "+scene.failure());scene.commit();
            check(!CartAuditContext.rollbackActive&&scene.report().applied==2,"commit did not release locks/count changes");
            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState()),target(B,Blocks.DIRT.defaultBlockState())));
            drain(scene);check(scene.ready(),"already recovered scene refused");scene.commit();
            check(scene.report().applied==0&&scene.report().skipped==2,"repeat applied recovered resources again");

            reset(level,A,Blocks.AIR.defaultBlockState());reset(level,B,Blocks.AIR.defaultBlockState());
            scene=new RecoveryScene(List.of(new RecoveryScene.Target(level,target(A,Blocks.STONE.defaultBlockState()))),1,128);
            drain(scene);check(scene.aborted()&&level.getBlockState(A).isAir(),"backup memory ceiling was checked after mutation");

            memoryRelease(level);
            reset(level,A,Blocks.AIR.defaultBlockState());
            reset(level,A.above(),Blocks.AIR.defaultBlockState());reset(level,A.below(),Blocks.STONE.defaultBlockState());
            var lower=Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF,DoubleBlockHalf.LOWER);
            var upper=lower.setValue(DoorBlock.HALF,DoubleBlockHalf.UPPER);
            scene=scene(level,List.of(target(A,lower)));drain(scene);
            check(scene.aborted()&&level.getBlockState(A).isAir(),"unpaired door partially restored");
            reset(level,A.below(),Blocks.AIR.defaultBlockState());
            scene=scene(level,List.of(target(A,lower),target(A.above(),upper)));drain(scene);
            check(scene.aborted()&&level.getBlockState(A).isAir()&&level.getBlockState(A.above()).isAir(),"unsupported door survived precommit checks and could emit later drops");
            reset(level,A.below(),Blocks.STONE.defaultBlockState());
            scene=scene(level,List.of(target(A,lower),target(A.above(),upper)));drain(scene);
            check(scene.ready(),"complete door refused: "+scene.failure());scene.commit();
            check(level.getBlockState(A).is(Blocks.OAK_DOOR)&&level.getBlockState(A.above()).is(Blocks.OAK_DOOR),"door halves did not survive commit");
            System.out.println("RECOVERY_SCENE_NATIVE_OK: cross-chunk preflight, locks, whole-scene cancellation, late slot failure compensation, commit/repeat, backup memory ceiling, complete/incomplete door");
        } finally {
            for (var entry:original.entrySet()) {
                reset(level,entry.getKey(),NbtSerde.readBlockState(level,entry.getValue().state()));
                if(entry.getValue().be()!=null)NbtSerde.readBlockEntity(level,entry.getKey(),entry.getValue().be());
            }
            check(!CartAuditContext.rollbackActive,"scene fixture leaked reservations");
        }
    }
    private static void journalWaitCancellation(ServerLevel level) {
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        RecoveryScene scene=null;
        try {
            var field=RecoveryJournal.class.getDeclaredField("IO");field.setAccessible(true);
            ((java.util.concurrent.ExecutorService)field.get(null)).execute(()->{entered.countDown();try{release.await();}catch(InterruptedException problem){Thread.currentThread().interrupt();}});
            check(entered.await(5,TimeUnit.SECONDS),"journal fixture could not reserve I/O worker");
            scene=scene(level,List.of(target(A,Blocks.STONE.defaultBlockState())));
            advanceTo(scene,RecoveryScene.Phase.WRITE_JOURNAL);
            check(level.getBlockState(A).isAir(),"world changed before durable journal");
            scene.cancel("cancel while the journal is still being written");
            check(!scene.aborted()&&CartAuditContext.rollbackActive,"cancel unlocked a scene before its pending journal was resolved");
        } catch(ReflectiveOperationException|InterruptedException problem){throw new AssertionError(problem);}
        finally {release.countDown();if(scene!=null){scene.cancel("journal fixture cleanup");drain(scene);}}
        check(scene.aborted()&&level.getBlockState(A).isAir()&&!CartAuditContext.rollbackActive,"cancelled pending journal leaked a reservation or mutated the world");
        System.out.println("RECOVERY_JOURNAL_WAIT_NATIVE_OK: no mutation before durability; cancellation waits for pending write and safely retires it before unlock");
    }
    private static void staleStorageHandles(ServerLevel level) {
        reset(level,A,Blocks.CHEST.defaultBlockState());
        var empty=NbtSerde.snapshotBlockEntityAt(level,A);var slots=ContainerSlotSnapshot.snapshotTag(level,A);
        var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(A);
        chest.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,2));
        var state=net.minecraft.nbt.NbtUtils.writeBlockState(level.getBlockState(A));
        var before=new RollbackEngine.ReplayCheckpoint(state,NbtSerde.snapshotBlockEntityAt(level,A),ContainerSlotSnapshot.snapshotTag(level,A));
        var after=new RollbackEngine.ReplayCheckpoint(state,empty,slots);
        var target=new RollbackEngine.PreparedBlockSnapshot(A,state.toString(),empty.toString(),slots.toString(),ActionType.BLOCK_ENTITY_NBT_CHANGE,
                state,empty,slots,state,List.of(before,after));
        var stale=new net.neoforged.neoforge.items.wrapper.InvWrapper(chest);
        var combined=new net.minecraft.world.CompoundContainer(chest,new net.minecraft.world.SimpleContainer(27));
        var menu=new net.minecraft.world.inventory.AbstractContainerMenu(null,0) {
            {addSlot(new net.minecraft.world.inventory.Slot(combined,0,0,0));}
            public net.minecraft.world.item.ItemStack quickMoveStack(net.minecraft.world.entity.player.Player player,int index){return net.minecraft.world.item.ItemStack.EMPTY;}
            public boolean stillValid(net.minecraft.world.entity.player.Player player){return true;}
        };
        var scene=scene(level,List.of(target));advanceTo(scene,RecoveryScene.Phase.RECHECK);
        boolean previous=CartAuditContext.restoring(false);
        try {
            check(stale.extractItem(0,1,false).isEmpty()&&chest.getItem(0).getCount()==2,"unregistered old InvWrapper bypassed native container reservation");
            check(CartRestoreLocks.menuTouchesLocked(menu),"already open CompoundContainer menu bypassed scene reservation");
            var fresh=new net.neoforged.neoforge.fluids.capability.templates.FluidTank(1000);
            CartAutomationHooks.exposed(level,A,null,fresh);
            check(fresh.fill(new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,500),
                    net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)==0,"newly exposed handler escaped scene reservation");
        } finally {CartAuditContext.restoring(previous);scene.cancel("storage handle fixture");drain(scene);reset(level,A,Blocks.AIR.defaultBlockState());}
        System.out.println("RECOVERY_STORAGE_LOCK_NATIVE_OK: pre-existing InvWrapper, CompoundContainer menu and newly exposed fluid handler cannot bypass scene reservations");
    }
    private static void simulationLocks(ServerLevel level) {
        var scene=scene(level,List.of(target(A,Blocks.SAND.defaultBlockState())));
        advanceTo(scene,RecoveryScene.Phase.DATA);
        boolean restoring=CartAuditContext.restoring(false);
        try {
            int falling=level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,new net.minecraft.world.phys.AABB(A).inflate(3)).size();
            var tick=ServerLevel.class.getDeclaredMethod("tickBlock",BlockPos.class,net.minecraft.world.level.block.Block.class);tick.setAccessible(true);tick.invoke(level,A,Blocks.SAND);
            check(level.getBlockState(A).is(Blocks.SAND)&&level.getEntitiesOfClass(net.minecraft.world.entity.item.FallingBlockEntity.class,new net.minecraft.world.phys.AABB(A).inflate(3)).size()==falling,
                    "scheduled tick duplicated a reserved falling block");
            check(level.getBlockTicks().hasScheduledTick(A,Blocks.SAND),"reserved block tick was discarded instead of rescheduled");
            int drops=level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(A).inflate(3)).size();
            var explosion=new net.minecraft.world.level.Explosion(level,null,A.getX()+.5,A.getY()+.5,A.getZ()+.5,2,false,net.minecraft.world.level.Explosion.BlockInteraction.DESTROY,List.of(A));
            explosion.finalizeExplosion(false);
            check(explosion.getToBlow().isEmpty()&&level.getBlockState(A).is(Blocks.SAND)
                    &&level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(A).inflate(3)).size()==drops,
                    "explosion emitted loot from a reserved block");
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
        finally {CartAuditContext.restoring(restoring);scene.cancel("simulation lock fixture");drain(scene);}
        check(level.getBlockState(A).isAir(),"simulation fixture compensation left falling block");
        scene=scene(level,List.of(target(A,Blocks.WATER.defaultBlockState())));advanceTo(scene,RecoveryScene.Phase.DATA);
        restoring=CartAuditContext.restoring(false);
        try {
            var tick=ServerLevel.class.getDeclaredMethod("tickFluid",BlockPos.class,net.minecraft.world.level.material.Fluid.class);tick.setAccessible(true);tick.invoke(level,A,net.minecraft.world.level.material.Fluids.WATER);
            check(level.getFluidTicks().hasScheduledTick(A,net.minecraft.world.level.material.Fluids.WATER),"reserved fluid tick was lost");
            check(level.getBlockState(A.below()).isAir(),"fluid simulation escaped reserved source during staging");
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
        finally {CartAuditContext.restoring(restoring);scene.cancel("fluid lock fixture");drain(scene);}
        System.out.println("RECOVERY_SIMULATION_LOCK_NATIVE_OK: falling block/fluid ticks postponed without losing scheduling; explosion excluded reserved positions before loot");
    }
    private static void memoryRelease(ServerLevel level) {
        reset(level,A,Blocks.CHEST.defaultBlockState());
        var empty=NbtSerde.snapshotBlockEntityAt(level,A);
        var emptySlots=ContainerSlotSnapshot.snapshotTag(level,A);
        var chest=(net.minecraft.world.level.block.entity.ChestBlockEntity)level.getBlockEntity(A);
        chest.getPersistentData().putByteArray("RecoveryMemoryProbe",new byte[8*1024*1024]);
        chest.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,2));
        var state=net.minecraft.nbt.NbtUtils.writeBlockState(level.getBlockState(A));
        var before=new RollbackEngine.ReplayCheckpoint(state,NbtSerde.snapshotBlockEntityAt(level,A),ContainerSlotSnapshot.snapshotTag(level,A));
        var after=new RollbackEngine.ReplayCheckpoint(state,empty,emptySlots);
        var target=new RollbackEngine.PreparedBlockSnapshot(A,state.toString(),empty.toString(),emptySlots.toString(),ActionType.BLOCK_ENTITY_NBT_CHANGE,
                state,empty,emptySlots,state,List.of(before,after));
        var scene=scene(level,List.of(target));
        advanceTo(scene,RecoveryScene.Phase.RECHECK);
        var reference=backupReference(scene);
        check(reference.get()!=null,"scene did not retain its backup before commit");
        drain(scene);check(scene.ready(),"memory release scene failed: "+scene.failure());scene.commit();
        for(int i=0;i<8&&reference.get()!=null;i++) {
            System.gc();
            try {Thread.sleep(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError(interrupted);}
        }
        check(reference.get()==null,"completed scene retained detached 8 MiB backup NBT");
        check(!chest.getPersistentData().contains("RecoveryMemoryProbe"),"target NBT retained removed persistent data");
        System.out.println("RECOVERY_SCENE_MEMORY_RELEASE_OK: detached 8 MiB backup becomes collectable after commit; temporary world locks released");
    }
    private static java.lang.ref.WeakReference<CompoundTag> backupReference(RecoveryScene scene) {
        try {
            var field=RecoveryScene.class.getDeclaredField("saved");field.setAccessible(true);
            var row=((List<?>)field.get(scene)).getFirst();
            var accessor=row.getClass().getDeclaredMethod("before");accessor.setAccessible(true);
            return new java.lang.ref.WeakReference<>(((RollbackEngine.ReplayCheckpoint)accessor.invoke(row)).be());
        } catch(ReflectiveOperationException failure) {throw new AssertionError(failure);}
    }
    private static RecoveryScene scene(ServerLevel level,List<RollbackEngine.PreparedBlockSnapshot> blocks) {
        return new RecoveryScene(blocks.stream().map(block->new RecoveryScene.Target(level,block)).toList(),64L*1024*1024,128);
    }
    private static RollbackEngine.PreparedBlockSnapshot target(BlockPos pos,BlockState state) {return target(pos,state,null,null);}
    private static RollbackEngine.PreparedBlockSnapshot target(BlockPos pos,BlockState state,CompoundTag be,CompoundTag slots) {
        var air=net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState());
        var tag=net.minecraft.nbt.NbtUtils.writeBlockState(state);
        return new RollbackEngine.PreparedBlockSnapshot(pos,tag.toString(),NbtSerde.toSnbt(be),NbtSerde.toSnbt(slots),ActionType.BLOCK_PLACE,
                tag,be,slots,air,List.of(new RollbackEngine.ReplayCheckpoint(air,null,null),new RollbackEngine.ReplayCheckpoint(tag,be,slots)));
    }
    private static void advanceTo(RecoveryScene scene,RecoveryScene.Phase phase) {
        for(int i=0;i<5000&&scene.phase()!=phase&&!scene.aborted();i++)step(scene);
        check(scene.phase()==phase,"scene did not reach "+phase+": "+scene.phase()+" "+scene.failure());
    }
    private static void drain(RecoveryScene scene) {
        for(int i=0;i<5000&&!scene.ready()&&!scene.aborted()&&!scene.quarantined();i++)step(scene);
        check(scene.ready()||scene.aborted(),"scene did not terminate: "+scene.phase()+" "+scene.failure());
    }
    private static void step(RecoveryScene scene) {
        scene.step(1,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),8);
        if(scene.phase()==RecoveryScene.Phase.WRITE_JOURNAL)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    private static void reset(ServerLevel level,BlockPos pos,BlockState state) {level.removeBlockEntity(pos);level.setBlock(pos,Blocks.AIR.defaultBlockState(),2|16|32|64);level.setBlock(pos,state,2|16|32|64);}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
