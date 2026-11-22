package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.api.connectivity.ConnectivityHandler;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Real Create tank topology, shared capability reservations and whole-scene fluid recovery. */
final class FluidRecoveryNativeSmoke {
    private static final BlockPos A=new BlockPos(160,188,160),B=new BlockPos(192,188,160);
    private static final List<BlockPos> POS=List.of(A,A.above(),B);
    private record States(List<RollbackEngine.ReplayCheckpoint> values) {}
    static void process(ServerLevel level,String mode) throws Exception {
        for(var pos:POS){level.getChunk(pos);for(var side:Direction.values())level.getChunk(pos.relative(side));}
        if(mode.equals("fluid-verify")) {
            check(tank(level,A).getHeight()==2&&tank(level,A.above()).getController().equals(A),"startup fluid topology changed");assertAmounts(level,4000,0);
            var directory=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/avilixlogger-recovery");
            try(var files=java.nio.file.Files.list(directory)){check(files.noneMatch(path->path.toString().endsWith(".scene")),"fluid startup retained pending WAL");}
            System.out.println("FLUID_RESTART_NATIVE_OK: saved partial fluid update undone before gameplay; exact 4000 mB and shared two-block topology preserved");level.getServer().halt(false);return;
        }
        check(mode.equals("fluid-crash"),"unknown fluid process mode");
        boolean old=CartAuditContext.restoring(true);
        try {
            for(var pos:POS){level.removeBlockEntity(pos);level.setBlock(pos,AllBlocks.FLUID_TANK.getDefaultState(),2|16|32|64);}
            ConnectivityHandler.formMulti(tank(level,A));ConnectivityHandler.formMulti(tank(level,B));for(var pos:POS)tank(level,pos).preventConnectivityUpdate();
            tank(level,A).getTankInventory().setFluid(new FluidStack(Fluids.WATER,4000));tank(level,B).getTankInventory().setFluid(FluidStack.EMPTY);
        } finally {CartAuditContext.restoring(old);}
        var before=states(level);tank(level,A).getTankInventory().drain(3000,IFluidHandler.FluidAction.EXECUTE);tank(level,B).getTankInventory().fill(new FluidStack(Fluids.WATER,3000),IFluidHandler.FluidAction.EXECUTE);
        var after=states(level);restore(level,before);var scene=scene(level,before,after);advance(scene,RecoveryScene.Phase.DATA);step(scene);assertAmounts(level,1000,0);
        RecoveryCheckpoint.flush(level.getServer());System.out.println("FLUID_FORCED_CRASH_READY");System.out.flush();Runtime.getRuntime().halt(77);
    }
    static void run(ServerLevel level) throws Exception {
        for(var pos:POS){level.getChunk(pos);for(var side:Direction.values())level.getChunk(pos.relative(side));}
        var original=states(level);RecoveryScene scene=null;
        try {
            boolean old=CartAuditContext.restoring(true);
            try {
                for(var pos:POS){level.removeBlockEntity(pos);level.setBlock(pos,AllBlocks.FLUID_TANK.getDefaultState(),2|16|32|64);}
                ConnectivityHandler.formMulti(tank(level,A));ConnectivityHandler.formMulti(tank(level,B));
                for(var pos:POS)tank(level,pos).preventConnectivityUpdate();
                check(tank(level,A).getHeight()==2&&tank(level,A.above()).getController().equals(A),"native tank topology did not form");
                tank(level,A).getTankInventory().setFluid(new FluidStack(Fluids.WATER,4000));tank(level,B).getTankInventory().setFluid(FluidStack.EMPTY);
            } finally {CartAuditContext.restoring(old);}
            var before=states(level);var source=tank(level,A).getTankInventory();var child=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,A.above(),null);
            check(child==source,"child capability does not alias root inventory");
            source.drain(3000,IFluidHandler.FluidAction.EXECUTE);tank(level,B).getTankInventory().fill(new FluidStack(Fluids.WATER,3000),IFluidHandler.FluidAction.EXECUTE);
            var after=states(level);restore(level,before);
            source=tank(level,A).getTankInventory();child=level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.FluidHandler.BLOCK,A.above(),null);
            scene=scene(level,before,after).preflightOnly();drain(scene);check(scene.ready(),"fluid preview refused: "+scene.failure());scene.completePreflight();assertAmounts(level,4000,0);
            scene=scene(level,before,after);advance(scene,RecoveryScene.Phase.WRITE_JOURNAL);
            check(source.fill(new FluidStack(Fluids.WATER,1000),IFluidHandler.FluidAction.EXECUTE)==0&&child.drain(1000,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"saved/shared tank handler bypassed reservation");
            scene.cancel("fluid cancellation");drain(scene);check(scene.aborted(),scene.failure());assertAmounts(level,4000,0);
            scene=scene(level,before,after);drain(scene);check(scene.ready(),"fluid restoration refused: "+scene.failure());scene.commit();assertAmounts(level,1000,3000);
            scene=scene(level,before,after);drain(scene);check(scene.ready(),"repeat current fluid scene failed: "+scene.failure());scene.commit();assertAmounts(level,1000,3000);

            restore(level,before);scene=scene(level,before,after);advance(scene,RecoveryScene.Phase.VERIFY);scene.cancel("fluid late cancellation");drain(scene);check(scene.aborted(),scene.failure());assertAmounts(level,4000,0);
            // Persisted destination plus old source would create excess fluid: refuse before WAL.
            restore(level,before);old=CartAuditContext.restoring(true);
            try{tank(level,B).getTankInventory().setFluid(new FluidStack(Fluids.WATER,3000));}finally{CartAuditContext.restoring(old);}
            var mixed=states(level);scene=scene(level,mixed,after);drain(scene);
            check(scene.aborted()&&scene.failure().contains("fluid_resource_graph")&&!scene.changedWorld(),"mixed persistence silently deleted or minted fluid");assertAmounts(level,4000,3000);
            restore(level,before);
            var missing=new ArrayList<RecoveryScene.Target>();missing.add(target(level,A,before.values().get(0),after.values().get(0)));missing.add(target(level,B,before.values().get(2),after.values().get(2)));
            scene=new RecoveryScene(missing,32L*1024*1024,128);drain(scene);
            check(scene.aborted()&&scene.failure().contains("fluid_multiblock_incomplete")&&!scene.changedWorld(),"tank part outside selection was accepted");assertAmounts(level,4000,0);
            scene=new RecoveryScene(List.of(target(level,A.above(),before.values().get(1),after.values().get(1))),32L*1024*1024,128);drain(scene);
            check(scene.aborted()&&scene.failure().contains("fluid_controller_outside_scene")&&!scene.changedWorld(),"child-only selection resolved a shared handler outside its scene");
            var roots=List.of(target(level,A,before.values().get(0),after.values().get(0)),target(level,B,before.values().get(2),after.values().get(2)));
            var scope=new RollbackPlanManager.Scope(level.dimension().location().toString(),A,B.above());
            scene=new RecoveryScene(roots,32L*1024*1024,128).includeFluidDependencies(List.of(scope));drain(scene);
            check(scene.ready(),"unchanged unlogged tank part was not included: "+scene.failure());scene.commit();assertAmounts(level,1000,3000);
            System.out.println("FLUID_RECOVERY_NATIVE_OK: real two-block Create tank, cross-chunk closed transfer, shared handler locks, preview, commit/repeat, early/late cancellation, mixed-persistence and missing-part refusal");
        } finally {
            if(scene!=null&&!scene.aborted()&&scene.phase()!=RecoveryScene.Phase.CLOSED){scene.cancel("fluid fixture cleanup");drain(scene);}
            restore(level,original);check(!CartAuditContext.rollbackActive,"fluid fixture leaked reservations");
        }
    }
    private static FluidTankBlockEntity tank(ServerLevel level,BlockPos pos){return (FluidTankBlockEntity)level.getBlockEntity(pos);}
    private static States states(ServerLevel level){return new States(POS.stream().map(pos->new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(level.getBlockState(pos)),NbtSerde.snapshotBlockEntityAt(level,pos),ContainerSlotSnapshot.snapshotTag(level,pos))).toList());}
    private static void restore(ServerLevel level,States value) {
        boolean old=CartAuditContext.restoring(true);
        try {
            for(int i=0;i<POS.size();i++){var pos=POS.get(i);var state=value.values().get(i);level.removeBlockEntity(pos);level.setBlock(pos,NbtSerde.readBlockState(level,state.state()),2|16|32|64);}
            for(int i=0;i<POS.size();i++){var pos=POS.get(i);var state=value.values().get(i);if(state.be()!=null)check(NbtSerde.readBlockEntity(level,pos,state.be()),"fixture restore NBT");if(state.slots()!=null)check(ContainerSlotSnapshot.apply(level,pos,state.slots()),"fixture restore slots");}
        } finally {CartAuditContext.restoring(old);}
    }
    private static RecoveryScene.Target target(ServerLevel level,BlockPos pos,RollbackEngine.ReplayCheckpoint before,RollbackEngine.ReplayCheckpoint after){return new RecoveryScene.Target(level,new RollbackEngine.PreparedBlockSnapshot(pos,NbtSerde.toSnbt(after.state()),NbtSerde.toSnbt(after.be()),NbtSerde.toSnbt(after.slots()),ActionType.BLOCK_ENTITY_NBT_CHANGE,after.state(),after.be(),after.slots(),before.state(),List.of(before,after)));}
    private static RecoveryScene scene(ServerLevel level,States before,States after){var targets=new ArrayList<RecoveryScene.Target>();for(int i=0;i<POS.size();i++)targets.add(target(level,POS.get(i),before.values().get(i),after.values().get(i)));return new RecoveryScene(targets,32L*1024*1024,128);}
    private static void assertAmounts(ServerLevel level,int first,int second){check(tank(level,A).getTankInventory().getFluidAmount()==first&&tank(level,B).getTankInventory().getFluidAmount()==second,"fluid amount changed unexpectedly");}
    private static void step(RecoveryScene scene){scene.step(1,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),8);if(Set.of(RecoveryScene.Phase.RESOURCE_CHECK,RecoveryScene.Phase.WRITE_JOURNAL).contains(scene.phase()))java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
    private static void drain(RecoveryScene scene){for(int i=0;i<10000&&!scene.ready()&&!scene.aborted()&&!scene.quarantined();i++)step(scene);check(scene.ready()||scene.aborted(),"fluid scene stalled: "+scene.phase()+" "+scene.failure());}
    private static void advance(RecoveryScene scene,RecoveryScene.Phase phase){for(int i=0;i<10000&&!scene.aborted()&&scene.phase()!=phase;i++)step(scene);check(scene.phase()==phase,"fluid phase missing: "+scene.phase()+" "+scene.failure());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
