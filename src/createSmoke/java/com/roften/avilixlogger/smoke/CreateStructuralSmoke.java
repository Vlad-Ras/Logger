package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.*;
import com.simibubi.create.content.contraptions.bearing.*;
import com.simibubi.create.content.contraptions.piston.*;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import java.lang.reflect.*;
import java.util.*;

/** Genuine bearing assembly/disassembly and the production incremental transaction, without auth overrides. */
public final class CreateStructuralSmoke {
    public static void run(ServerLevel world)throws Exception {
        var pos=world.getSharedSpawnPos().offset(24,44,24);world.getChunk(pos);
        int tested=0;
        for(String mode:List.of("commit","replacement","mechanism","capture-limit","cancel-world","cancel-effect","cancel-spawn","conflict-world","conflict-effect","uuid-conflict","apply-failure","locks","incomplete","failure")) {
            var target=pos.above();
            world.setBlock(pos,AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING,Direction.UP),3);
            world.setBlock(target,Blocks.CHEST.defaultBlockState(),3);
            var diamond=new ItemStack(Items.DIAMOND,7);diamond.set(DataComponents.CUSTOM_NAME,Component.literal("structural components"));
            ((ChestBlockEntity)world.getBlockEntity(target)).setItem(5,diamond.copy());
            var controller=(MechanicalBearingBlockEntity)world.getBlockEntity(pos);controller.assemble();
            var bodies=((CreateControllerAccess)controller).avilixlogger$controlled();
            check(bodies.size()==1,"native bearing assembly failed: "+controller.getLastAssemblyException());
            var original=bodies.getFirst();var uuid=original.getUUID();var logical=CreateContraptionSources.id(original);
            field(MechanicalBearingBlockEntity.class,"angle").setFloat(controller,37);original.setAngle(37);
            check(world.getBlockState(target).isAir(),"assembled chest remains in world");
            boolean replacement=mode.equals("replacement")||mode.equals("cancel-effect")||mode.equals("cancel-spawn")||mode.equals("conflict-effect");
            if(replacement)world.setBlock(target,Blocks.DIRT.defaultBlockState(),3);
            var cargoCapture=mode.equals("mechanism")?CreateCargoAudit.captureChanges(original):null;
            var context=new com.simibubi.create.content.contraptions.behaviour.MovementContext(world,original.getContraption().getBlocks().get(BlockPos.ZERO),original.getContraption());
            var operation=mode.equals("mechanism")?CreateCargoAudit.operation(context,"native_self_disassembly",true):null;
            var capture=CreateStructuralAudit.capture(controller);
            if(mode.equals("capture-limit"))field(capture.getClass(),"bytes").setLong(capture,128L*1024*1024);
            try {controller.disassemble();if(!mode.equals("failure"))capture.success();}finally{capture.close();if(operation!=null)operation.close();if(cargoCapture!=null)cargoCapture.close();}
            check(original.isRemoved()&&world.getBlockState(target).is(Blocks.CHEST),"native disassembly failed");
            check(ItemStack.matches(diamond,((ChestBlockEntity)world.getBlockEntity(target)).getItem(5)),"unmounted cargo/components missing");
            var before=capture.before();var after=capture.after();
            check(capture.rows().size()==1,"controller disassembly duplicated history");
            var row=capture.rows().getFirst();row.materializeSnapshots();check(NbtSerde.fromSnbt(row.beAfter).hasUUID("Operation"),"mechanism context overwrote the structural operation UUID");
            try {
                if(mode.equals("capture-limit")){check(!after.getBoolean("Complete")&&after.contains("Failure"),"capture failure lost its incomplete history marker");reject(()->CreateStructuralRollbackPlan.prepare(before,after),"capture limit accepted for undo");tested++;continue;}
                check(before.getList("World",10).size()==2,"full controller/chest world snapshots missing");
                check(before.getList("World",10).getCompound(0).getCompound("BE").getBoolean("Running")&&!after.getList("World",10).getCompound(0).getCompound("BE").getBoolean("Running"),"controller state not captured around native call");
                if(mode.equals("failure")){reject(()->CreateStructuralRollbackPlan.prepare(before,after),"failed native call accepted");tested++;continue;}
                if(mode.equals("incomplete")){after.putBoolean("Complete",false);reject(()->CreateStructuralRollbackPlan.prepare(before,after),"incomplete native call accepted");tested++;continue;}
                var plan=CreateStructuralRollbackPlan.prepare(before,after);
                check(!replacement||!plan.effects().isEmpty(),"replacement drop missing from structural capture");
                var corrupted=before.copy();corrupted.getList("Bodies",10).getCompound(0).getCompound("Entity").remove("UUID");reject(()->CreateStructuralRollbackPlan.prepare(corrupted,after),"missing UUID accepted");
                if(mode.equals("conflict-world"))((ChestBlockEntity)world.getBlockEntity(target)).setItem(5,new ItemStack(Items.EMERALD,7));
                if(mode.equals("conflict-effect"))((ItemEntity)world.getEntity(plan.effects().getFirst().getUUID("Entity"))).setItem(new ItemStack(Items.EMERALD));
                if(mode.equals("uuid-conflict")){var copy=net.minecraft.world.entity.EntityType.loadEntityRecursive(before.getList("Bodies",10).getCompound(0).getCompound("Entity").copy(),world,e->e);check(copy!=null&&world.addFreshEntity(copy),"UUID conflict fixture spawn failed");}
                if(mode.equals("apply-failure")){
                    // Valid address, but an impossible BE payload forces failure after the first write.
                    before.getList("World",10).getCompound(0).getCompound("BE").putInt("ImpossibleRoundTrip",1);plan=CreateStructuralRollbackPlan.prepare(before,after);
                }
                boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try {
                    var type=Class.forName("com.roften.avilixlogger.compat.create.CreateStructuralRollbackCoordinator$Job");
                    var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateStructuralRollbackPlan.class,List.class,long.class);ctor.setAccessible(true);
                    Object job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,List.of(new StagedContraptionRestore(plan.bodies().getFirst().before().getCompound("Entity"))),1);
                    var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);var cancel=type.getDeclaredMethod("cancel",String.class);cancel.setAccessible(true);
                    var applied=field(type,"applied");var removed=field(type,"removed");var spawned=field(type,"spawned");var committed=field(type,"committed");
                    boolean canceled=false,rejected=false;int calls=0;
                    while(true) {
                        boolean done;
                        try {done=(Boolean)step.invoke(job,world.getServer());}
                            catch(InvocationTargetException failure){if(mode.equals("apply-failure")){check(applied.getInt(job)>0,"write failure was not injected after mutation");cancel.invoke(job,failure.getCause().getMessage());canceled=true;continue;}if(!mode.startsWith("conflict")&&!mode.equals("uuid-conflict"))throw failure;check(applied.getInt(job)==0,"preflight conflict modified world");rejected=true;break;}
                        if(mode.equals("locks")&&!canceled&&applied.getInt(job)>0) {
                            CartAuditContext.restoring(false);
                            try {check(!world.setBlock(target,Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved world accepts changes");controller.assemble();controller.disassemble();check(((CreateControllerAccess)controller).avilixlogger$controlled().isEmpty(),"reserved controller mutated");checkWorld(world,plan.world().getFirst().before());}
                            finally{CartAuditContext.restoring(true);}canceled=true;
                        }
                        if(!canceled&&((mode.equals("cancel-world")&&applied.getInt(job)>0)||(mode.equals("cancel-effect")&&removed.getInt(job)>0)||(mode.equals("cancel-spawn")&&spawned.getInt(job)>0))){cancel.invoke(job,"fixture cancellation");canceled=true;}
                        if(done)break;check(++calls<500,"structural Job did not terminate");
                    }
                    if(mode.startsWith("conflict")||mode.equals("uuid-conflict")){check(rejected&&(mode.equals("uuid-conflict")||world.getEntity(uuid)==null),"conflict restored entity");}
                    else if(mode.startsWith("cancel")||mode.equals("apply-failure")) {
                        check(!committed.getBoolean(job)&&world.getEntity(uuid)==null,"cancellation kept construction");
                        for(var endpoint:plan.world())checkWorld(world,endpoint.after());
                        for(var effect:plan.effects())check(world.getEntity(effect.getUUID("Entity"))!=null,"compensation lost replacement drop");
                        check(((CreateControllerAccess)controller).avilixlogger$controlled().isEmpty(),"compensation kept controller reference");
                    } else {
                        check(committed.getBoolean(job),"structural Job not committed");var restored=(ControlledContraptionEntity)world.getEntity(uuid);
                        check(restored!=null&&logical.equals(CreateContraptionSources.id(restored))&&controller.isAttachedTo(restored),"identity/controller binding lost");
                        check(world.getBlockState(target).is(replacement?Blocks.DIRT:Blocks.AIR),"world replacement not undone");
                        check(ItemStack.matches(diamond,restored.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(5)),"restored cargo/components differ");
                        for(var effect:plan.effects())check(world.getEntity(effect.getUUID("Entity"))==null,"replacement drop duplicated");
                    }
                }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();}
                tested++;
            }finally {
                boolean old=CartAuditContext.restoring(true);
                try {var entity=world.getEntity(uuid);if(entity instanceof CartEntityAccess access)access.avilixlogger$discardForRollback();((CreateControllerAccess)controller).avilixlogger$clearControlled();
                    for(var raw:capture.after().getList("Effects",10)){var entityDrop=world.getEntity(((CompoundTag)raw).getUUID("Entity"));if(entityDrop!=null)entityDrop.discard();}
                    world.setBlock(target,Blocks.AIR.defaultBlockState(),3);world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
                }finally{CartAuditContext.restoring(old);CreateStructuralAudit.clear();}
            }
        }
        for(String kind:List.of("clockwork","piston","pulley","mirror","windmill","windmill-conflict","windmill-locks","nested","elevator","elevator-empty","elevator-conflict","elevator-locks"))for(boolean cancel:List.of(false,true)){controllers(world,pos,kind,cancel);tested++;}
        for(String kind:List.of("bearing","clockwork","piston","pulley","mirror","windmill","nested","elevator","elevator-empty"))for(boolean cancel:List.of(false,true)){controllers(world,pos,kind,cancel,true);tested++;}
        for(String removalMode:List.of("set-air","set-stone","set-chest","cancel-first","world-conflict","apply-failure","locks")){controllers(world,pos,"bearing",removalMode.startsWith("cancel"),true,removalMode);tested++;}
        System.out.println("CREATE_CONTROLLER_REMOVAL_NATIVE_OK: 25 scenarios; destroyBlock including controller drop, full body/world/cargo/registry restoration and compensation");
        System.out.println("CREATE_STRUCTURAL_NATIVE_OK: "+tested+" scenarios; native bearing/two-hand clockwork/piston/pulley/mirror/windmill/stabilized assembly/disassembly, full controller state, components, replacement drops, commit, cancellation after world/effect/entity mutations, conflicts, locks and incomplete captures");
    }
    private static void controllers(ServerLevel world,BlockPos pos,String kind,boolean cancelJob)throws Exception {controllers(world,pos,kind,cancelJob,false);}
    private static void controllers(ServerLevel world,BlockPos pos,String kind,boolean cancelJob,boolean externalRemoval)throws Exception {controllers(world,pos,kind,cancelJob,externalRemoval,"destroy");}
    private static void controllers(ServerLevel world,BlockPos pos,String kind,boolean cancelJob,boolean externalRemoval,String removalMode)throws Exception {
        boolean elevator=kind.startsWith("elevator"),windmill=kind.startsWith("windmill");
        var state=switch(elevator?"elevator":windmill?"windmill":kind){case "bearing"->AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING,Direction.UP);case "clockwork"->AllBlocks.CLOCKWORK_BEARING.getDefaultState().setValue(ClockworkBearingBlock.FACING,Direction.UP);case "piston"->AllBlocks.MECHANICAL_PISTON.getDefaultState().setValue(MechanicalPistonBlock.FACING,Direction.UP);case "elevator"->AllBlocks.ELEVATOR_PULLEY.getDefaultState();case "windmill"->AllBlocks.WINDMILL_BEARING.getDefaultState().setValue(BearingBlock.FACING,Direction.UP);case "nested"->AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING,Direction.UP);default->AllBlocks.ROPE_PULLEY.getDefaultState();};
        boolean pulley=kind.equals("pulley")||kind.equals("mirror")||elevator;
        var target=pulley?pos.below(2):pos.above();
        world.setBlock(pos,state,3);world.setBlock(target,Blocks.CHEST.defaultBlockState(),3);
        if(pulley&&!elevator)world.setBlock(pos.below(),AllBlocks.PULLEY_MAGNET.getDefaultState(),3);
        var diamond=new ItemStack(Items.DIAMOND,7);diamond.set(DataComponents.CUSTOM_NAME,Component.literal(kind+" components"));((ChestBlockEntity)world.getBlockEntity(target)).setItem(5,diamond.copy());
        if(kind.equals("clockwork")){world.setBlock(pos.above(2),Blocks.CHEST.defaultBlockState(),3);((ChestBlockEntity)world.getBlockEntity(pos.above(2))).setItem(2,new ItemStack(Items.EMERALD,9));}
        if(kind.equals("piston"))for(int i=1;i<=2;i++)world.setBlock(pos.below(i),AllBlocks.PISTON_EXTENSION_POLE.getDefaultState().setValue(PistonExtensionPoleBlock.FACING,Direction.UP),3);
        var glue=new ArrayList<com.simibubi.create.content.contraptions.glue.SuperGlueEntity>();
        if(kind.equals("mirror")){
            world.setBlock(pos.east(),AllBlocks.ROPE_PULLEY.getDefaultState(),3);
            world.setBlock(pos.east().below(),AllBlocks.PULLEY_MAGNET.getDefaultState(),3);
            world.setBlock(target.east(),Blocks.CHEST.defaultBlockState(),3);
            var entity=new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(world,new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(target),net.minecraft.world.phys.Vec3.atLowerCornerOf(target.east().offset(1,1,1))));world.addFreshEntity(entity);glue.add(entity);
        }
        if(windmill){
            for(int x=-1;x<=1;x++)for(int z=-1;z<=1;z++)if(x!=0||z!=0)world.setBlock(target.offset(x,0,z),AllBlocks.SAIL.getDefaultState(),3);
            var entity=new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(world,new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(target.offset(-1,0,-1)),net.minecraft.world.phys.Vec3.atLowerCornerOf(target.offset(2,1,2))));world.addFreshEntity(entity);glue.add(entity);
            world.setBlock(pos.below(),AllBlocks.SHAFT.getDefaultState().setValue(net.minecraft.world.level.block.RotatedPillarBlock.AXIS,Direction.Axis.Y),3);
        }
        if(elevator){
            world.setBlock(target.east(),AllBlocks.REDSTONE_CONTACT.getDefaultState().setValue(com.simibubi.create.content.redstone.contact.RedstoneContactBlock.FACING,Direction.EAST),3);
            var entity=new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(world,new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(target),net.minecraft.world.phys.Vec3.atLowerCornerOf(target.east().offset(1,1,1))));world.addFreshEntity(entity);glue.add(entity);
            if(!kind.equals("elevator-empty")){
                var contact=target.east(2);world.setBlock(contact,AllBlocks.ELEVATOR_CONTACT.getDefaultState().setValue(com.simibubi.create.content.redstone.contact.RedstoneContactBlock.FACING,Direction.WEST),3);
                var coords=new com.simibubi.create.content.contraptions.elevator.ElevatorColumn.ColumnCoords(contact.getX(),contact.getZ(),Direction.WEST);
                com.simibubi.create.content.contraptions.elevator.ElevatorColumn.getOrCreate(world,coords).add(contact);
            }
        }
        if(kind.equals("nested")){
            world.setBlock(target.east(),AllBlocks.MECHANICAL_BEARING.getDefaultState().setValue(BearingBlock.FACING,Direction.UP),3);
            world.setBlock(target.east().above(),Blocks.CHEST.defaultBlockState(),3);
            ((ChestBlockEntity)world.getBlockEntity(target.east().above())).setItem(8,new ItemStack(Items.GOLD_INGOT,11));
            var entity=new com.simibubi.create.content.contraptions.glue.SuperGlueEntity(world,new net.minecraft.world.phys.AABB(net.minecraft.world.phys.Vec3.atLowerCornerOf(target),net.minecraft.world.phys.Vec3.atLowerCornerOf(target.east().offset(1,1,1))));world.addFreshEntity(entity);glue.add(entity);
        }
        var controller=(KineticBlockEntity)world.getBlockEntity(pos);
        List<AbstractContraptionEntity> originals=List.of();CreateStructuralAudit.Scope capture=null;
        try {
            if(!windmill)controller.setSpeed(kind.equals("piston")?-64:64);
            check(!controller.isRemoved()&&!CreateStructuralAudit.controllerLocked(controller),kind+" controller unavailable before assembly: "+world.getBlockState(pos));
            check(!world.getBlockState(target).isAir(),kind+" anchor empty before assembly");
            var assemble=method(controller.getClass(),"assemble");assemble.setAccessible(true);assemble.invoke(controller);
            originals=new ArrayList<>(((CreateControllerAccess)controller).avilixlogger$controlled());check(originals.size()==(kind.equals("clockwork")?2:1),kind+" native assembly failed: "+(controller instanceof MechanicalBearingBlockEntity b?b.getLastAssemblyException():"no bearing exception"));
            if(kind.equals("mirror"))check(net.minecraft.nbt.NbtUtils.readBlockPos(NbtSerde.snapshotBlockEntity(world,world.getBlockEntity(pos.east())),"MirrorParent").orElseThrow().equals(pos),"native mirror relationship missing");
            if(windmill)check(((WindmillBearingBlockEntity)controller).getGeneratedSpeed()!=0&&((KineticBlockEntity)world.getBlockEntity(pos.below())).getSpeed()!=0,"windmill kinetic load unpowered");
            if(kind.equals("nested")){var root=originals.getFirst();root.getContraption().onEntityInitialize(world,root);originals.addAll(root.getPassengers().stream().filter(AbstractContraptionEntity.class::isInstance).map(AbstractContraptionEntity.class::cast).toList());check(originals.size()==2,"native stabilized child missing");}
            if(kind.equals("clockwork")){field(ClockworkBearingBlockEntity.class,"hourAngle").setFloat(controller,37);field(ClockworkBearingBlockEntity.class,"minuteAngle").setFloat(controller,12);((ControlledContraptionEntity)originals.get(0)).setAngle(37);((ControlledContraptionEntity)originals.get(1)).setAngle(12);}
            if(elevator){var ec=(com.simibubi.create.content.contraptions.elevator.ElevatorContraption)originals.getFirst().getContraption();var column=com.simibubi.create.content.contraptions.elevator.ElevatorColumn.get(world,ec.getGlobalColumn());column.target(target.getY()-7);column.namesChanged();}
            if(elevator){field(com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity.class,"prevSpeed").setFloat(controller,0.375f);field(com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity.class,"arrived").setBoolean(controller,false);field(com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity.class,"clientOffsetTarget").setInt(controller,target.getY()-7);}
            capture=externalRemoval?CreateStructuralAudit.captureRemoval(controller):CreateStructuralAudit.capture(controller);var disassemble=method(controller.getClass(),"disassemble");disassemble.setAccessible(true);
            try{if(externalRemoval){
                if(removalMode.startsWith("set-"))check(world.setBlock(pos,(removalMode.equals("set-stone")?Blocks.STONE:removalMode.equals("set-chest")?Blocks.CHEST:Blocks.AIR).defaultBlockState(),3),"native controller replacement failed");
                else check(world.destroyBlock(pos,true),"native controller destruction failed");
            }else disassemble.invoke(controller);capture.success();}finally{capture.close();}
            var plan=CreateStructuralRollbackPlan.prepare(capture.before(),capture.after());
            if(externalRemoval){
                var beforeRemoval=capture.before();var afterRemoval=capture.after();
                check(beforeRemoval.getList("World",10).getCompound(0).getBoolean("ControllerRemoved"),"controller deletion not marked");
                if(!removalMode.startsWith("set-"))check(plan.effects().stream().anyMatch(t->t.getString("EntityType").equals("minecraft:item")),"destroyBlock controller item not captured");
                var invalid=beforeRemoval.copy();invalid.getList("World",10).getCompound(0).remove("ControllerRemoved");reject(()->CreateStructuralRollbackPlan.prepare(invalid,afterRemoval),"asymmetric deletion marker accepted");
                if(removalMode.equals("world-conflict"))world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3);
                if(removalMode.equals("apply-failure")){beforeRemoval.getList("World",10).getCompound(0).getCompound("BE").putInt("ImpossibleRoundTrip",1);plan=CreateStructuralRollbackPlan.prepare(beforeRemoval,afterRemoval);}
            }
            if(kind.equals("mirror")){check(plan.world().stream().filter(e->e.before().getBoolean("Controller")).count()==2,"mirror controller snapshot incomplete");var broken=capture.before();broken.getList("World",10).removeIf(raw->((CompoundTag)raw).getLong("Pos")==pos.east().asLong());var intactAfter=capture.after();reject(()->CreateStructuralRollbackPlan.prepare(broken,intactAfter),"missing mirror participant accepted");}
            if(!glue.isEmpty())check(plan.effects().stream().anyMatch(e->e.getString("EntityType").equals("create:super_glue")),"native glue effect not captured");
            boolean old=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
            try {
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateStructuralRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateStructuralRollbackPlan.class,List.class,long.class);ctor.setAccessible(true);
                var staged=plan.bodies().stream().map(body->new StagedContraptionRestore(body.before().getCompound("Entity"))).toList();var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,staged,1);
                if(kind.equals("windmill-conflict")){
                    var saved=plan.world().stream().map(CreateStructuralRollbackPlan.World::after).filter(t->t.contains("KineticNetworks")).findFirst().orElseThrow().getList("KineticNetworks",10).getCompound(0);
                    var unexpected=new com.simibubi.create.content.kinetics.KineticNetwork();unexpected.id=saved.getLong("Id");CreateKineticRegistry.networks(world).put(unexpected.id,unexpected);
                    var stepConflict=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);stepConflict.setAccessible(true);boolean rejected=false;
                    try{while(!(Boolean)stepConflict.invoke(job,world.getServer())){}}catch(InvocationTargetException failure){check(field(type,"applied").getInt(job)==0,"kinetic registry conflict modified world");rejected=true;}
                    check(rejected,"kinetic registry conflict accepted");CreateKineticRegistry.networks(world).remove(unexpected.id);return;
                }
                if(kind.equals("elevator-conflict")){
                    var saved=plan.world().stream().map(CreateStructuralRollbackPlan.World::after).filter(t->t.contains("ElevatorColumn")).findFirst().orElseThrow().getCompound("ElevatorColumn");
                    com.simibubi.create.content.contraptions.elevator.ElevatorColumn.getOrCreate(world,CreateElevatorRegistry.validate(saved)).target(target.getY()-17);
                    var stepConflict=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);stepConflict.setAccessible(true);boolean rejected=false;
                    try{while(!(Boolean)stepConflict.invoke(job,world.getServer())){}}catch(InvocationTargetException failure){check(field(type,"applied").getInt(job)==0,"column conflict modified world");rejected=true;}
                    check(rejected,"column target conflict accepted");return;
                }
                var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);var cancel=type.getDeclaredMethod("cancel",String.class);cancel.setAccessible(true);var spawned=field(type,"spawned");
                boolean canceled=false;int calls=0;
                boolean lockChecked=false;
                while(true){boolean done;
                    try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException problem){
                        if(removalMode.equals("world-conflict")){check(field(type,"applied").getInt(job)==0,"controller conflict mutated world");return;}
                        if(removalMode.equals("apply-failure")){check(field(type,"applied").getInt(job)>0,"controller failure preceded write");cancel.invoke(job,problem.getCause().getMessage());canceled=true;continue;}
                        throw problem;
                    }
                    if(removalMode.equals("cancel-first")&&!canceled&&field(type,"applied").getInt(job)>0){cancel.invoke(job,"controller removal after first write");canceled=true;}
                    if(removalMode.equals("locks")&&!lockChecked&&field(type,"applied").getInt(job)>0){
                        CartAuditContext.restoring(false);try{check(!world.destroyBlock(pos,true),"reserved destroyBlock produced a drop");check(!world.setBlock(pos,Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved controller accepted replacement");}finally{CartAuditContext.restoring(true);}lockChecked=true;
                    }
                    if(kind.equals("windmill-locks")&&!lockChecked&&field(type,"applied").getInt(job)>0){
                        var saved=plan.world().stream().map(CreateStructuralRollbackPlan.World::before).filter(t->t.contains("KineticNetworks")).findFirst().orElseThrow().getList("KineticNetworks",10).getCompound(0);long id=saved.getLong("Id");var current=CreateKineticRegistry.snapshot(world,id);var network=CreateKineticRegistry.networks(world).get(id);CartAuditContext.restoring(false);
                        try{network.updateStressFor(controller,999);network.updateCapacityFor(controller,999);network.remove(controller);network.initFromTE(999,999,999);network.sync();check(current.equals(CreateKineticRegistry.snapshot(world,id)),"reserved kinetic registry changed");}
                        finally{CartAuditContext.restoring(true);}lockChecked=true;
                    }
                    if(kind.equals("elevator-locks")&&!lockChecked&&field(type,"phase").getInt(job)>=1){
                        var saved=plan.world().stream().map(CreateStructuralRollbackPlan.World::after).filter(t->t.contains("ElevatorColumn")).findFirst().orElseThrow().getCompound("ElevatorColumn");var coords=CreateElevatorRegistry.validate(saved);var current=CreateElevatorRegistry.snapshot(world,coords);CartAuditContext.restoring(false);
                        try{var column=com.simibubi.create.content.contraptions.elevator.ElevatorColumn.getOrCreate(world,coords);column.target(-50);column.setActive(true);column.add(new BlockPos(coords.x(),target.getY()+2,coords.z()));column.remove(new BlockPos(coords.x(),target.getY(),coords.z()));column.namesChanged();check(current.equals(CreateElevatorRegistry.snapshot(world,coords)),"reserved native column changed");}
                        finally{CartAuditContext.restoring(true);}lockChecked=true;
                    }
                    if(cancelJob&&!canceled&&spawned.getInt(job)==staged.size()){cancel.invoke(job,"all controller bodies staged");canceled=true;}if(done)break;check(++calls<1000,kind+" Job did not terminate");}
                if(windmill)check((((KineticBlockEntity)world.getBlockEntity(pos.below())).getSpeed()!=0)==!cancelJob,"windmill load speed not restored/compensated");
                if(kind.equals("elevator-locks")||kind.equals("windmill-locks"))check(lockChecked,"column lock was not exercised");
                boolean compensated=cancelJob||removalMode.equals("apply-failure");
                if(removalMode.equals("world-conflict"))throw new AssertionError("controller conflict committed");
                if(removalMode.equals("locks"))check(lockChecked,"controller replacement lock not exercised");
                if(externalRemoval&&!compensated)controller=(KineticBlockEntity)world.getBlockEntity(pos);
                if(compensated){for(var endpoint:plan.world())checkWorld(world,endpoint.after());check(externalRemoval?!(world.getBlockEntity(pos) instanceof CreateControllerAccess):((CreateControllerAccess)controller).avilixlogger$controlled().isEmpty(),kind+" compensation kept controller reference");}
                else {for(var endpoint:plan.world())checkWorld(world,endpoint.before());check(field(type,"committed").getBoolean(job),kind+" not committed");check(((CreateControllerAccess)controller).avilixlogger$controlled().size()==(kind.equals("clockwork")?2:1),kind+" lost controller body");}
                for(var original:originals){var restored=world.getEntity(original.getUUID());check(compensated?restored==null:restored!=null&&(!(restored instanceof ControlledContraptionEntity)||((IControlContraption)controller).isAttachedTo((AbstractContraptionEntity)restored)),kind+" original identity not restored");}
            }finally{CartAuditContext.restoring(old);CartAuditContext.rollbackActive=false;CartAuditContext.LOCKED_ENTITIES.clear();CartRestoreLocks.clear();}
        }finally {
            boolean old=CartAuditContext.restoring(true);try {for(var entity:originals){var restored=world.getEntity(entity.getUUID());if(restored instanceof CartEntityAccess access)access.avilixlogger$discardForRollback();}((CreateControllerAccess)controller).avilixlogger$clearControlled();
                if(capture!=null)for(var raw:capture.after().getList("Effects",10)){var effect=world.getEntity(((CompoundTag)raw).getUUID("Entity"));if(effect!=null)effect.discard();}
                for(var entity:glue)if(!entity.isRemoved())entity.discard();
                for(int x=-2;x<=2;x++)for(int z=-2;z<=2;z++)for(int y=-3;y<=4;y++)world.setBlock(pos.offset(x,y,z),Blocks.AIR.defaultBlockState(),3);
                if(windmill)CreateKineticRegistry.networks(world).remove(pos.asLong());
                if(elevator)com.simibubi.create.content.contraptions.elevator.ElevatorColumn.LOADED_COLUMNS.get(world).remove(new com.simibubi.create.content.contraptions.elevator.ElevatorColumn.ColumnCoords(target.getX()+2,target.getZ(),Direction.WEST));
            }finally{CartAuditContext.restoring(old);CreateStructuralAudit.clear();}
        }
    }
    private static Method method(Class<?> type,String name)throws Exception{for(var current=type;current!=null;current=current.getSuperclass())try{return current.getDeclaredMethod(name);}catch(NoSuchMethodException ignored){}throw new NoSuchMethodException(name);}
    private static Field field(Class<?> type,String name)throws Exception{var field=type.getDeclaredField(name);field.setAccessible(true);return field;}
    private static void checkWorld(ServerLevel world,CompoundTag tag){var actual=CreateStructuralAudit.world(world,BlockPos.of(tag.getLong("Pos")),tag);var expected=tag.copy();expected.remove("Controller");expected.remove("ControllerRemoved");check(actual.equals(expected),"compensated world differs: "+actual+" != "+expected);}
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalArgumentException|IllegalStateException expected){return;}throw new AssertionError(message);}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
