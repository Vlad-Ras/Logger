package com.roften.avilixlogger.smoke;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.*;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.trains.entity.*;
import com.simibubi.create.content.trains.graph.*;
import com.simibubi.create.content.trains.station.*;
import com.simibubi.create.content.trains.track.TrackMaterial;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.*;
import java.lang.reflect.*;
import java.util.*;

public final class CreateTrainRollbackSmoke {
    @net.neoforged.fml.common.EventBusSubscriber(modid="avilixlogger")
    public static final class Probe {
        @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent event){
            if(!Boolean.getBoolean("avilixlogger.trainRollbackSmoke"))return;
            try{for(int repeat=0;repeat<Integer.getInteger("avilixlogger.trainSmokeRepeats",1);repeat++)run(event.getServer().overworld());}catch(Throwable failure){failure.printStackTrace();System.out.println("CREATE_TRAIN_ROLLBACK_FAILED");}finally{event.getServer().halt(false);}
        }
    }
    public static void run(ServerLevel world)throws Exception {
        var pos=world.getSharedSpawnPos().offset(40,44,40);world.getChunkAt(pos);
        var managerField=ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
        @SuppressWarnings("unchecked")var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)managerField.get(world);
        for(int x=0;x<3;x++)manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(pos.east(x*8)),net.minecraft.world.level.entity.Visibility.TRACKED);
        int tested=0;
        for(String mode:List.of("commit","cancel-world","cancel-spawn","cancel-registry","world-conflict","graph-conflict","graph-late","registry-conflict","apply-failure","locks","backwards","two-carriages","portal-refusal","unloaded-refusal","station-conflict","signal-busy","fluid-cargo","fluid-conflict","waiting-queue","schedule","signal-reserve","body-conflict","two-bogeys","representation-unloaded","representation-portal","representation-cancel","representation-cancel-portal","representation-cargo-conflict","representation-position-conflict","representation-graph-late","representation-uuid-conflict","representation-links-refusal","representation-missing-anchor","representation-locks","representation-spawn-reject","representation-spawn-throw","state-loaded","state-unloaded","state-portal","state-portal-corrupt-cutoff","state-portal-corrupt-position","state-portal-both","state-portal-unloaded","state-multiple","state-cancel","state-duplicate","state-loaded-duplicate","state-graph-conflict")){
            if(System.getProperty("avilixlogger.trainSmokeCase")!=null&&!mode.equals(System.getProperty("avilixlogger.trainSmokeCase")))continue;
            var graph=new TrackGraph();var a=new TrackNode(new TrackNodeLocation(Vec3.atLowerCornerOf(pos.west(8)).add(.5,0,.5)).in(world),TrackGraph.nextNodeId(),new Vec3(0,1,0));var b=new TrackNode(new TrackNodeLocation(Vec3.atLowerCornerOf(pos.east(32)).add(.5,0,.5)).in(world),TrackGraph.nextNodeId(),new Vec3(0,1,0));graph.addNode(a);graph.addNode(b);var edge=new TrackEdge(a,b,null,TrackMaterial.ANDESITE);graph.putConnection(a,b,edge);graph.putConnection(b,a,new TrackEdge(b,a,null,TrackMaterial.ANDESITE));Create.RAILWAYS.putGraphWithDefaultGroup(graph);
            var carriages=new ArrayList<Carriage>();var constructions=new ArrayList<CarriageContraption>();var originals=new ArrayList<CarriageContraptionEntity>();var glues=new ArrayList<SuperGlueEntity>();var stationPos=pos.north(3);CreateStructuralAudit.Scope capture=null;Train train=null;Train competingTrain=null;
            try{
                int count=mode.equals("two-carriages")||mode.equals("state-multiple")?2:1;
                for(int i=0;i<count;i++){
                    var bogey=pos.east(1+i*6);world.getChunkAt(bogey);world.setBlock(bogey,AllBlocks.SMALL_BOGEY.getDefaultState(),3);world.setBlock(bogey.above(),Blocks.CHEST.defaultBlockState(),3);
                    var item=new ItemStack(Items.DIAMOND,7+i);item.set(DataComponents.CUSTOM_NAME,Component.literal("train cargo "+i));((ChestBlockEntity)world.getBlockEntity(bogey.above())).setItem(5,item);
                    boolean twoBogeys=mode.equals("two-bogeys");if(twoBogeys){world.setBlock(bogey.east(4),AllBlocks.SMALL_BOGEY.getDefaultState(),3);for(int bridge=1;bridge<=4;bridge++)world.setBlock(bogey.east(bridge).above(),Blocks.STONE.defaultBlockState(),3);}
                    boolean fluid=mode.startsWith("fluid-");if(fluid){world.setBlock(bogey.above(2),AllBlocks.FLUID_TANK.getDefaultState(),3);var tank=(com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)world.getBlockEntity(bogey.above(2));var fluidStack=new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,2500);fluidStack.set(DataComponents.CUSTOM_NAME,Component.literal("train fluid components"));check(tank.getTankInventory().fill(fluidStack,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE)==2500,"native fluid setup rejected");}
                    var glue=new SuperGlueEntity(world,new AABB(Vec3.atLowerCornerOf(bogey),Vec3.atLowerCornerOf(bogey.offset(twoBogeys?5:1,fluid?3:2,1))));world.addFreshEntity(glue);glues.add(glue);
                    var cc=new CarriageContraption(Direction.EAST);check(cc.assemble(world,bogey),"native carriage assembly failed");cc.removeBlocksFromWorld(world,BlockPos.ZERO);constructions.add(cc);
                    double center=9+i*6;var cb=new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag(),new TravellingPoint(a,b,edge,center+.5,false),new TravellingPoint(a,b,edge,center-.5,false));var second=twoBogeys?new CarriageBogey(AllBlocks.SMALL_BOGEY.get(),false,new CompoundTag(),new TravellingPoint(a,b,edge,center+4.5,false),new TravellingPoint(a,b,edge,center+3.5,false)):null;carriages.add(new Carriage(cb,second,twoBogeys?4:0));
                }
                train=new Train(UUID.randomUUID(),UUID.randomUUID(),graph,carriages,count==2?List.of(6):List.of(),false,3);train.name=Component.literal("Native rollback train");Create.RAILWAYS.addTrain(train);
                for(int i=0;i<count;i++){
                    var carriage=carriages.get(i);carriage.setContraption(world,constructions.get(i));var dce=carriage.getDimensional(world);var create=dce.getClass().getDeclaredMethod("createEntity",net.minecraft.world.level.Level.class,boolean.class);create.setAccessible(true);create.invoke(dce,world,true);var entity=carriage.anyAvailableEntity();check(entity!=null&&world.getEntity(entity.getUUID())==entity,"native carriage representation missing");entity.yaw=0;entity.pitch=0;originals.add(entity);
                }
                world.setBlock(stationPos,AllBlocks.TRACK_STATION.getDefaultState(),3);var station=EdgePointType.STATION.create();station.id=UUID.randomUUID();station.blockEntityPos=stationPos;station.blockEntityDimension=world.dimension();station.setLocation(Couple.create(a.getLocation(),b.getLocation()),9);graph.addPoint(EdgePointType.STATION,station);train.currentStation=station.id;station.reserveFor(train);
                train.currentlyBackwards=mode.equals("backwards");train.throttle=.625;train.fuelTicks=41;train.honk=true;train.honkTicks=13;train.lowHonk=true;train.carriageWaitingForChunks=2;train.manualSteer=TravellingPoint.SteerDirection.LEFT;train.migrationCooldown=17;train.status.missingConductor();train.occupiedSignalBlocks.put(graph.id,null);Create.RAILWAYS.signalEdgeGroups.get(graph.id).trains.add(train);
                train.runtime.ticksInTransit=29;train.runtime.currentTitle="fixture title";train.runtime.displayLinkUpdateRequested=true;train.runtime.startCooldown();
                if(mode.equals("schedule")){var schedule=new com.simibubi.create.content.trains.schedule.Schedule();schedule.entries.add(new com.simibubi.create.content.trains.schedule.ScheduleEntry(new com.simibubi.create.content.trains.schedule.destination.DestinationInstruction(),new ArrayList<>()));train.runtime.setSchedule(schedule,false);train.runtime.paused=true;train.runtime.ticksInTransit=29;train.runtime.startCooldown();}
                if(mode.equals("waiting-queue"))((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$install(train,-1,0);
                if(mode.equals("signal-reserve")){var boundary=EdgePointType.SIGNAL.create();boundary.id=UUID.randomUUID();boundary.setLocation(Couple.create(a.getLocation(),b.getLocation()),12);graph.addPoint(EdgePointType.SIGNAL,boundary);train.reservedSignalBlocks.add(graph.id);Create.RAILWAYS.signalEdgeGroups.get(graph.id).reserved=boundary;train.navigation.waitingForSignal=net.createmod.catnip.data.Pair.of(boundary.id,true);train.navigation.distanceToSignal=3.5;train.navigation.ticksWaitingForSignal=9;}
                if(mode.startsWith("state-")){CreateTrainStateSmoke.run(world,train,mode);tested++;continue;}
                if(mode.startsWith("representation-")){representation(world,train,originals,mode,graph,a,b);tested++;continue;}
                if(mode.equals("portal-refusal")){
                    carriages.getFirst().getDimensional(net.minecraft.world.level.Level.NETHER);capture=CreateStructuralAudit.captureTrain(train);
                    var life=CreateLifecycleAudit.snapshot(originals.getFirst(),"entity");var representations=life.getList("RepresentationStatus",10);
                    var prepared=CreateTrainRepresentationState.prepare(life.getCompound("TrainRepresentations"));check(prepared.nativeCarriage().getList("EntityPositioning",10).size()==2,"portal lost dimensional payload");
                    check(representations.size()==2&&representations.getCompound(0).getBoolean("Loaded")!=representations.getCompound(1).getBoolean("Loaded"),"portal representations did not distinguish unloaded dimension");
                    try{check(!train.disassemble(Direction.EAST,pos),"native portal disassembly unexpectedly succeeded");}finally{capture.close();}
                    check(!capture.after().getBoolean("Complete")&&Create.RAILWAYS.trains.get(train.id)==train&&!originals.getFirst().isRemoved(),"portal refusal modified train or claimed completion");var portalBefore=capture.before();var portalAfter=capture.after();reject(()->CreateStructuralRollbackPlan.prepare(portalBefore,portalAfter),"portal refusal accepted for structural undo");tested++;continue;
                }
                if(mode.equals("unloaded-refusal")){
                    var dimensional=carriages.getFirst().getDimensional(world);var original=dimensional.entity;
                    dimensional.entity=new java.lang.ref.WeakReference<>(null);
                    try{check(!train.canDisassemble()&&!train.disassemble(Direction.EAST,pos),"unloaded carriage unexpectedly disassembled");
                        var life=CreateLifecycleAudit.snapshot(originals.getFirst(),"entity");var status=life.getList("RepresentationStatus",10);
                        var prepared=CreateTrainRepresentationState.prepare(life.getCompound("TrainRepresentations"));check(prepared.train().equals(train.id)&&!prepared.nativeCarriage().getCompound("Entity").isEmpty(),"unloaded train lost durable native payload");
                        check(status.size()==1&&!status.getCompound(0).getBoolean("Loaded"),"unloaded carriage logged as loaded");
                        var unloadedTrain=train;reject(()->CreateStructuralAudit.captureTrain(unloadedTrain),"unloaded carriage produced structural capture");
                        check(Create.RAILWAYS.trains.get(train.id)==train&&world.getEntity(originals.getFirst().getUUID())==originals.getFirst(),"unloaded refusal altered live representation");
                    }finally{dimensional.entity=original;}
                    tested++;continue;
                }
                var orientation=((CreateOrientationAccess)originals.getFirst()).avilixlogger$orientation();CreateCartAudit.save(originals.getFirst());CreateTrainRegistry.snapshot(train,world);check(orientation.equals(((CreateOrientationAccess)originals.getFirst()).avilixlogger$orientation()),"observational snapshot consumed train ForceYaw");
                if(mode.equals("commit")){
                    var life=CreateLifecycleAudit.snapshot(originals.getFirst(),"entity");
                    var context=life.getCompound("TrainRepresentations");var prepared=CreateTrainRepresentationState.prepare(context);var nativeCopy=prepared.nativeCarriage().copy();context.getCompound("Native").putBoolean("MutationAfterPrepare",true);check(prepared.nativeCarriage().equals(nativeCopy),"train preparation shares mutable history");
                    var broken=life.getCompound("TrainRepresentations").copy();broken.getCompound("Native").remove("EntityPositioning");reject(()->CreateTrainRepresentationState.prepare(broken),"missing dimensions accepted");
                    var loaded=life.getList("RepresentationStatus",10);
                    var leaving=CreateLifecycleAudit.snapshot(originals.getFirst(),"unloaded").getList("RepresentationStatus",10);
                    check(loaded.size()==1&&loaded.getCompound(0).getBoolean("Loaded")&&loaded.getCompound(0).getUUID("Entity").equals(originals.getFirst().getUUID())&&leaving.size()==1&&!leaving.getCompound(0).getBoolean("Loaded"),"lifecycle representation transition incorrect");
                }
                capture=CreateStructuralAudit.captureTrain(train);check(train.canDisassemble(),"native train cannot disassemble");
                boolean success;try{success=train.disassemble(Direction.EAST,pos);if(success)capture.success();}finally{capture.close();}check(success&&!Create.RAILWAYS.trains.containsKey(train.id),"native train disassembly failed");
                var before=capture.before();var after=capture.after();check(capture.rows().size()==1&&after.getCompound("Train").getBoolean("Registered")==false,"train operation not grouped by train ID");
                if(mode.equals("commit")){for(String fault:List.of("signals","typed-signals","station","orientation","carriage-id","graph","cargo")){var broken=before.copy();var root=broken.getCompound("Train");switch(fault){case "signals"->root.remove("Signals");case "typed-signals"->{var list=new ListTag();list.add(StringTag.valueOf("corrupt"));root.put("Signals",list);}case "station"->root.remove("Station");case "orientation"->broken.getList("Bodies",10).getCompound(0).remove("Orientation");case "carriage-id"->root.getCompound("Native").getList("Carriages",10).getCompound(0).remove("AvilixCarriageId");case "graph"->root.remove("Graph");case "cargo"->root.getCompound("Native").getList("Carriages",10).getCompound(0).getCompound("Entity").getCompound("Contraption").putBoolean("Stalled",true);}reject(()->CreateStructuralRollbackPlan.prepare(broken,after),"incomplete train manifest accepted: "+fault);}}
                var plan=CreateStructuralRollbackPlan.prepare(before,after);check(plan.bodies().size()==count,"not all carriages captured");check(station.nearestTrain.get()==null,"native station reservation not canceled");
                if(mode.equals("world-conflict")){var changed=plan.world().stream().map(CreateStructuralRollbackPlan.World::after).filter(t->t.getCompound("State").getString("Name").equals("minecraft:chest")).findFirst().orElseThrow();((ChestBlockEntity)world.getBlockEntity(BlockPos.of(changed.getLong("Pos")))).setItem(5,new ItemStack(Items.EMERALD,7));}
                if(mode.equals("fluid-conflict")){var changed=plan.world().stream().map(CreateStructuralRollbackPlan.World::after).filter(t->t.getCompound("State").getString("Name").equals("create:fluid_tank")).findFirst().orElseThrow();((com.simibubi.create.content.fluids.tank.FluidTankBlockEntity)world.getBlockEntity(BlockPos.of(changed.getLong("Pos")))).getTankInventory().drain(1,net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction.EXECUTE);}
                if(mode.equals("body-conflict")){var data=plan.bodies().getFirst().before().getCompound("Entity").copy();data.putUUID("UUID",UUID.randomUUID());var foreign=(CarriageContraptionEntity)net.minecraft.world.entity.EntityType.loadEntityRecursive(data,world,e->e);check(foreign!=null&&world.addFreshEntity(foreign),"foreign carriage collision setup failed");originals.add(foreign);}
                if(mode.equals("graph-conflict"))graph.disconnectNodes(a,b);
                if(mode.equals("station-conflict")||mode.equals("signal-busy")){competingTrain=new Train(UUID.randomUUID(),null,graph,new ArrayList<>(),List.of(),false,0);if(mode.equals("station-conflict"))station.reserveFor(competingTrain);else Create.RAILWAYS.signalEdgeGroups.get(graph.id).trains.add(competingTrain);}
                if(mode.equals("registry-conflict"))Create.RAILWAYS.addTrain(train);
                if(mode.equals("apply-failure")){before.getList("World",10).getCompound(0).getCompound("BE").putInt("RoundTripFault",1);plan=CreateStructuralRollbackPlan.prepare(before,after);}
                var readyPlan=plan;var staged=plan.bodies().stream().map(body->new StagedContraptionRestore(CreateTrainRegistry.payload(readyPlan.before(),body.before()))).toList();
                boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    var type=Class.forName("com.roften.avilixlogger.compat.create.CreateStructuralRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateStructuralRollbackPlan.class,List.class,long.class);ctor.setAccessible(true);var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,staged,1L);var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);var cancel=type.getDeclaredMethod("cancel",String.class);cancel.setAccessible(true);var applied=field(type,"applied");var spawned=field(type,"spawned");boolean canceled=false,rejected=false,lockChecked=false;int calls=0;
                    while(true){boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException failure){if(List.of("world-conflict","graph-conflict","registry-conflict","station-conflict","signal-busy","fluid-conflict","body-conflict").contains(mode)){check(applied.getInt(job)==0,"preflight conflict mutated world");rejected=true;break;}if(List.of("apply-failure","graph-late").contains(mode)){check(applied.getInt(job)>0,"late failure did not follow mutation");cancel.invoke(job,failure.getCause().getMessage());canceled=true;continue;}throw new IllegalStateException("train scenario "+mode,failure);}
                        if(!canceled&&((mode.equals("cancel-world")&&applied.getInt(job)>0)||(mode.equals("cancel-spawn")&&spawned.getInt(job)>0)||(mode.equals("cancel-registry")&&field(type,"phase").getInt(job)==7))){cancel.invoke(job,"native train cancellation");canceled=true;}
                        if(mode.equals("graph-late")&&!canceled&&applied.getInt(job)>0)graph.disconnectNodes(a,b);
                        if(mode.equals("locks")&&!lockChecked&&field(type,"phase").getInt(job)==7){var existing=Create.RAILWAYS.trains.get(train.id);var saved=CreateTrainRegistry.snapshot(existing,world);CartAuditContext.restoring(false);try{existing.tick(world);existing.earlyTick(world);existing.disassemble(Direction.EAST,pos);Create.RAILWAYS.removeTrain(existing.id);station.cancelReservation(existing);existing.runtime.discardSchedule();existing.runtime.startCooldown();existing.navigation.cancelNavigation();var stationBe=(StationBlockEntity)world.getBlockEntity(stationPos);check(!stationBe.updateName("blocked"),"reserved station accepted update");var handler=world.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK,stationPos,null);check(handler.insertItem(0,new ItemStack(Items.DIAMOND),false).getCount()==1&&handler.extractItem(0,1,false).isEmpty(),"reserved depot exchanged items");check(Create.RAILWAYS.trains.get(existing.id)==existing&&saved.equals(CreateTrainRegistry.snapshot(existing,world)),"reserved train or station changed");}finally{CartAuditContext.restoring(true);}lockChecked=true;}
                        if(done)break;check(++calls<2000,"train transaction did not terminate");
                    }
                    if(List.of("world-conflict","graph-conflict","registry-conflict","station-conflict","signal-busy","fluid-conflict","body-conflict").contains(mode))check(rejected,"conflict accepted: "+mode);
                    else if(canceled){check(!field(type,"committed").getBoolean(job)&&!Create.RAILWAYS.trains.containsKey(train.id),"compensation kept train registry");for(var endpoint:plan.world())checkWorld(world,endpoint.after());for(var body:plan.bodies())check(world.getEntity(body.before().getUUID("StorageEntity"))==null,"compensation kept carriage");check(station.nearestTrain.get()==null,"compensation kept station reservation");check(Create.RAILWAYS.signalEdgeGroups.get(graph.id).trains.contains(train),"compensation lost the original transient signal reference");}
                    else {check(field(type,"committed").getBoolean(job),"train not committed");var restored=Create.RAILWAYS.trains.get(train.id);check(restored!=null&&restored.carriages.size()==count&&station.nearestTrain.get()==restored,"train/station registry not restored");for(var body:plan.bodies()){var entity=(CarriageContraptionEntity)world.getEntity(body.before().getUUID("StorageEntity"));check(entity!=null&&entity.getCarriage()==restored.carriages.get(entity.carriageIndex)&&((ContraptionIdentity)entity.getCarriage()).avilixlogger$identity().equals(body.before().getUUID("Carriage")),"carriage identity/reference lost");check(entity.getContraption().getStorage()==entity.getCarriage().storage,"cargo manager proxy detached");}if(mode.equals("locks"))check(lockChecked,"reservation not exercised");}
                }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();}tested++;
            }finally{
                java.lang.ref.Reference.reachabilityFence(competingTrain);
                boolean old=CartAuditContext.restoring(true);try{if(train!=null){var registered=Create.RAILWAYS.trains.get(train.id);if(registered!=null)for(var carriage:registered.carriages)carriage.forEachPresentEntity(e->{if(!e.isRemoved())((CartEntityAccess)e).avilixlogger$discardForRollback();});((CreateRailwayAccess)Create.RAILWAYS).avilixlogger$remove(train.id);}for(var entity:originals)if(!entity.isRemoved())((CartEntityAccess)entity).avilixlogger$discardForRollback();if(capture!=null){for(var raw:capture.after().getList("Effects",10)){var effect=world.getEntity(((CompoundTag)raw).getUUID("Entity"));if(effect!=null)effect.discard();}for(var raw:capture.before().getList("World",10))world.setBlock(BlockPos.of(((CompoundTag)raw).getLong("Pos")),Blocks.AIR.defaultBlockState(),3);}for(var glue:glues)if(!glue.isRemoved())glue.discard();world.setBlock(stationPos,Blocks.AIR.defaultBlockState(),3);for(int i=0;i<14;i++){world.setBlock(pos.east(i),Blocks.AIR.defaultBlockState(),3);world.setBlock(pos.east(i).above(),Blocks.AIR.defaultBlockState(),3);world.setBlock(pos.east(i).above(2),Blocks.AIR.defaultBlockState(),3);}Create.RAILWAYS.removeGraphAndGroup(graph);}finally{CartAuditContext.restoring(old);CreateStructuralAudit.clear();}
            }
        }
        System.out.println("CREATE_TRAIN_ROLLBACK_NATIVE_OK: "+tested+" scenarios; genuine bogey/chest carriage assembly and train disassembly, RailwayManager/queues/station/signals/cargo, original UUIDs, backwards and multi-carriage, commit, compensation, conflicts and locks");
    }
    private static void representation(ServerLevel world,Train train,List<CarriageContraptionEntity> originals,String mode,TrackGraph graph,TrackNode a,TrackNode b)throws Exception {
        var carriage=train.carriages.getFirst();var first=originals.getFirst();var nether=world.getServer().getLevel(net.minecraft.world.level.Level.NETHER);
        boolean portal=mode.contains("portal")||mode.endsWith("locks");
        if(portal){
            check(nether!=null,"missing test dimension");var other=carriage.getDimensional(nether);other.positionAnchor=first.position().add(0,0,16);other.rotationAnchors=Couple.create(other.positionAnchor.add(1,0,0),other.positionAnchor.add(-1,0,0));nether.getChunkAt(BlockPos.containing(other.positionAnchor));
            var managerField=ServerLevel.class.getDeclaredField("entityManager");managerField.setAccessible(true);
            @SuppressWarnings("unchecked")var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)managerField.get(nether);
            manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(BlockPos.containing(other.positionAnchor)),net.minecraft.world.level.entity.Visibility.TRACKED);
        }
        var snapshot=CreateTrainRepresentationState.capture(first);var cargo=carriage.storage;int version=cargo.getVersion();
        if(mode.endsWith("missing-anchor")){
            var left=snapshot.copy();var right=snapshot.copy();var forward=new CompoundTag();forward.putInt("Aa",1);forward.putInt("BB",2);var reverse=new CompoundTag();reverse.putInt("BB",2);reverse.putInt("Aa",1);
            left.getCompound("Native").getCompound("Entity").getCompound("Contraption").put("HashCollisionFixture",forward);right.getCompound("Native").getCompound("Entity").getCompound("Contraption").put("HashCollisionFixture",reverse);
            var fingerprint=CreateTrainRepresentationRecovery.class.getDeclaredMethod("fingerprint",CreateTrainRepresentationState.Prepared.class);fingerprint.setAccessible(true);
            check(java.util.Arrays.equals((byte[])fingerprint.invoke(null,CreateTrainRepresentationState.prepare(left)),(byte[])fingerprint.invoke(null,CreateTrainRepresentationState.prepare(right))),"NBT key insertion order changed conflict witness");
        }
        if(mode.endsWith("missing-anchor")){var broken=snapshot.copy();broken.getCompound("Native").getList("EntityPositioning",10).getCompound(0).remove("PositionAnchor");reject(()->new CreateTrainRepresentationRecovery.Prepared(broken),"missing anchor accepted");return;}
        if(mode.endsWith("links-refusal")){var broken=snapshot.copy();broken.getCompound("Native").getCompound("Passengers").put("Seat0",new CompoundTag());reject(()->new CreateTrainRepresentationRecovery.Prepared(broken),"passenger contract bypassed");return;}
        var prepared=new CreateTrainRepresentationRecovery.Prepared(snapshot);
        boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;CreateTrainRepresentationRecovery.Job job=null;
        var original=carriage.getDimensional(world);boolean removeFirst=!portal||mode.contains("cancel-portal");
        java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener=event->{
            if(event.getEntity() instanceof CarriageContraptionEntity ce&&ce.getCarriage()==carriage){
                if(mode.endsWith("spawn-reject"))event.setCanceled(true);
                if(mode.endsWith("spawn-throw"))throw new IllegalStateException("injected representation join failure");
            }
        };
        try{
            if(removeFirst){((CartEntityAccess)first).avilixlogger$discardForRollback();original.entity=new java.lang.ref.WeakReference<>(null);}
            if(mode.endsWith("cargo-conflict"))snapshot.getCompound("Native").getList("EntityPositioning",10).getCompound(0).putInt("RoundTripConflict",1);
            if(mode.endsWith("cargo-conflict")){var tag=snapshot.copy();var slots=tag.getCompound("Native").getList("EntityPositioning",10).getCompound(0);slots.remove("items");prepared=new CreateTrainRepresentationRecovery.Prepared(tag);}
            if(mode.endsWith("position-conflict")){var positions=snapshot.getCompound("Native").getList("EntityPositioning",10);var anchor=positions.getCompound(0).getList("PositionAnchor",6);anchor.set(0,DoubleTag.valueOf(anchor.getDouble(0)+1));prepared=new CreateTrainRepresentationRecovery.Prepared(snapshot);}
            if(mode.endsWith("uuid-conflict")){var impostor=(CarriageContraptionEntity)net.minecraft.world.entity.EntityType.loadEntityRecursive(snapshot.getCompound("Native").getCompound("Entity"),world,e->e);check(impostor!=null&&world.addFreshEntity(impostor),"UUID conflict setup failed");originals.add(impostor);}
            try{job=new CreateTrainRepresentationRecovery.Job(world.getServer(),prepared);}catch(IllegalStateException failure){if(mode.endsWith("cargo-conflict")||mode.endsWith("position-conflict")||mode.endsWith("uuid-conflict"))return;throw failure;}
            check(!mode.endsWith("cargo-conflict")&&!mode.endsWith("position-conflict")&&!mode.endsWith("uuid-conflict"),"representation conflict accepted");
            if(mode.contains("spawn-"))net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(listener);
            var spawned=CreateTrainRepresentationRecovery.Job.class.getDeclaredField("spawned");spawned.setAccessible(true);boolean canceled=false,late=false,locked=false;int calls=0;
            while(true){
                boolean done;
                try{done=job.step();}catch(IllegalStateException failure){if((mode.endsWith("graph-late")||mode.contains("spawn-"))&&!late){late=true;canceled=true;job.cancel();continue;}throw failure;}
                if(mode.endsWith("graph-late")&&!late&&spawned.getInt(job)>0)graph.disconnectNodes(a,b);
                if(mode.contains("cancel")&&!canceled&&spawned.getInt(job)>0){job.cancel();canceled=true;}
                if(mode.endsWith("locks")&&!locked){var before=CreateTrainRegistry.snapshot(train,world);CartAuditContext.restoring(false);try{train.tick(world);train.earlyTick(world);Create.RAILWAYS.removeTrain(train.id);check(Create.RAILWAYS.trains.get(train.id)==train&&CreateTrainRegistry.snapshot(train,world).equals(before),"representation train lock failed");check(CreateCargoAudit.restoreLocked(first),"existing portal representation not locked");}finally{CartAuditContext.restoring(true);}locked=true;}
                if(done)break;check(++calls<2000,"representation job did not terminate");
            }
            if(job.committed())check((removeFirst?world:nether).getEntity(job.result().getUUID())==job.result(),"recovery receipt refers to another dimension/entity");
            check(job.committed()!=canceled,"representation commit/compensation mismatch");check(carriage.storage==cargo&&cargo.getVersion()==version,"representation replaced/reset cargo");
            for(var dimension:carriage.getPresentDimensions()){var entity=carriage.getDimensionalIfPresent(dimension).entity.get();if(canceled&&(removeFirst||!dimension.equals(world.dimension())))check(entity==null||entity.isRemoved(),"compensation retained a new representation");else check(entity!=null&&!entity.isRemoved()&&entity.getCarriage()==carriage&&entity.getContraption().getStorage()==cargo,"representation not bound to common cargo");}
            if(portal&&!removeFirst)check(world.getEntity(first.getUUID())==first&&original.entity.get()==first,"existing representation was replaced");
            if(mode.contains("spawn-"))check(late,"spawn rejection/exception not exercised");
            if(mode.endsWith("graph-late"))check(late,"late graph conflict was not exercised");if(mode.endsWith("locks"))check(locked,"representation locks not exercised");
        }finally{
            if(mode.contains("spawn-"))net.neoforged.neoforge.common.NeoForge.EVENT_BUS.unregister(listener);
            if(job!=null&&!job.committed()){job.cancel();int calls=0;while(!job.step())check(++calls<100,"cleanup compensation did not terminate");}
            for(var dimension:carriage.getPresentDimensions()){var entity=carriage.getDimensionalIfPresent(dimension).entity.get();if(entity!=null&&!entity.isRemoved()&&!originals.contains(entity))((CartEntityAccess)entity).avilixlogger$discardForRollback();}
            CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();
        }
    }
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalStateException|IllegalArgumentException expected){return;}throw new AssertionError(message);}
    private static Field field(Class<?> type,String name)throws Exception{var value=type.getDeclaredField(name);value.setAccessible(true);return value;}
    private static void checkWorld(ServerLevel world,CompoundTag expected){var actual=CreateStructuralAudit.world(world,BlockPos.of(expected.getLong("Pos")),expected);var copy=expected.copy();copy.remove("Controller");check(actual.equals(copy),"compensated train world differs");}
    private static void check(boolean result,String message){if(!result)throw new AssertionError(message);}
}
