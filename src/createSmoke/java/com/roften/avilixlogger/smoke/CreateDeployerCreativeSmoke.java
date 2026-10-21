package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.*;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.tuple.MutablePair;
import java.lang.reflect.*;
import java.util.*;

/** Native take/return with creative supply/void, using the same production reserved Job. */
public final class CreateDeployerCreativeSmoke {
    @net.neoforged.fml.common.EventBusSubscriber(modid="avilixlogger") public static final class Probe {
        @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent e){
            if(!Boolean.getBoolean("avilixlogger.deployerCreativeSmoke"))return;
            try{run(e.getServer().overworld());}catch(Throwable failure){failure.printStackTrace();System.out.println("CREATE_DEPLOYER_CREATIVE_FAILED");}finally{e.getServer().halt(false);}
        }
    }
    public static void run(ServerLevel world)throws Exception{
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class);
        var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        var pos=world.getSharedSpawnPos().offset(44,44,44);method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class).invoke(null,world,pos);
        int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))
        for(String exchange:List.of("take","return","mixed-return"))
        for(String mode:List.of("undo","cancel-first","cancel-all","hand-conflict","source-conflict","late-source-conflict","late-verify-source-conflict","locks","quantity","components","missing-io","missing-finite","missing-hand","missing-phase","missing-header","old-format","wrong-owner","failure","place","place-cancel-all","place-world-conflict","place-missing-use","world-effect","world-cancel-all","world-conflict","entity-effect","entity-cancel-all","entity-conflict","external-item","nested-capture","nested-during","noop","oversized","finite-conflict","empty-template")){
            if(mode.startsWith("place")&&!exchange.equals("take"))continue;
            boolean inbound=!exchange.equals("take"),mixed=exchange.equals("mixed-return");
            if((mode.equals("finite-conflict")||mode.equals("missing-finite"))&&!mixed)continue;
            if(mode.equals("empty-template")&&!inbound)continue;
            if(mode.equals("external-item")&&!inbound)continue;
            var body=(AbstractContraptionEntity)factory.invoke(null,world,kind);attach.invoke(null,body);
            body.setPos(pos.above(5).getX(),pos.above(5).getY(),pos.above(5).getZ());check(world.addFreshEntity(body),"creative deployer construction spawn");
            net.minecraft.world.entity.Entity target=null;
            try{
                world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);world.setBlock(pos.below(),Blocks.STONE.defaultBlockState(),3);
                var storage=body.getContraption().getStorage();var finite=storage.getAllItemStorages().get(BlockPos.ZERO);
                var supplied=named(Items.STONE,1,"creative deployer supplied component");
                var crate=new CreativeCrateMountedStorage(mode.equals("empty-template")?ItemStack.EMPTY:supplied);var access=(CartStorageAccessor)storage;access.avilixlogger$reset();
                if(mixed)access.avilixlogger$addItem(finite,BlockPos.ZERO);
                var local=new BlockPos(4,0,0);access.avilixlogger$addItem(crate,local);method(storage.getClass(),"initialize").invoke(storage);CartStorageAudit.bind(body);
                var input=named(Items.DIAMOND,mode.equals("oversized")?1024:5,"creative deployer consumed component");
                if(mixed){for(int slot=0;slot<finite.getSlots();slot++)finite.setStackInSlot(slot,input.copyWithCount(64));finite.setStackInSlot(26,input.copyWithCount(63));}
                var finiteBefore=new ArrayList<ItemStack>();if(mixed)for(int slot=0;slot<finite.getSlots();slot++)finiteBefore.add(finite.getStackInSlot(slot).copy());
                var settings=new CompoundTag();settings.putUUID("Owner",UUID.randomUUID());settings.putString("Mode","USE");
                var info=new StructureBlockInfo(new BlockPos(7,0,0),AllBlocks.DEPLOYER.getDefaultState(),settings);
                var context=new MovementContext(world,info,body.getContraption());context.position=Vec3.atCenterOf(pos);body.getContraption().getActors().add(MutablePair.of(info,context));
                var fake=new DeployerFakePlayer(world,settings.getUUID("Owner"));context.temporaryData=fake;fake.getInventory().selected=1;
                if(inbound){fake.setItemInHand(InteractionHand.MAIN_HAND,mode.equals("external-item")?com.simibubi.create.AllItems.WRENCH.asStack():named(Items.EMERALD,1,"retained selected hand"));if(!mode.equals("noop")){
                    fake.getInventory().setItem(3,input);fake.getInventory().setItem(37,named(Items.IRON_CHESTPLATE,1,"void armor component"));fake.getInventory().setItem(40,named(Items.GOLD_INGOT,3,"void offhand component"));}}
                else{fake.getInventory().setItem(37,new ItemStack(Items.IRON_CHESTPLATE));if(mode.equals("noop"))fake.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.EMERALD));}
                if(mode.startsWith("entity-")){target=net.minecraft.world.entity.EntityType.SHEEP.create(world);target.setPos(Vec3.atCenterOf(pos));check(world.addFreshEntity(target),"mixed creative target spawn");}
                var beforeHand=CreateDeployerTransaction.hand(context,fake);var template=crate.getStackInSlot(0).copy();LogEntry row;
                CartAutomationAudit.Scope nested=null;
                if(mode.startsWith("nested-")){world.setBlock(pos.east(4),AllBlocks.FLUID_TANK.getDefaultState(),3);if(mode.equals("nested-capture"))nested=CartAutomationAudit.captureChanges(world.getBlockEntity(pos.east(4)));}
                try(var session=CreateDeployerAudit.capture(context,pos,fake,"USE")){
                    if(mode.equals("nested-during"))nested=CartAutomationAudit.captureChanges(world.getBlockEntity(pos.east(4)));
                    try{java.lang.invoke.MethodHandles.privateLookupIn(DeployerMovementBehaviour.class,java.lang.invoke.MethodHandles.lookup()).findVirtual(DeployerMovementBehaviour.class,inbound?"tryDisposeOfExcess":"tryGrabbingItem",java.lang.invoke.MethodType.methodType(void.class,MovementContext.class)).invokeWithArguments(new DeployerMovementBehaviour(),context);}
                    catch(Throwable failure){throw new IllegalStateException("Native creative deployer IO",failure);}
                    if(mode.startsWith("place")){
                        var modeClass=Class.forName("com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity$Mode");
                        try{java.lang.invoke.MethodHandles.publicLookup().findVirtual(DeployerMovementBehaviour.class,"activate",java.lang.invoke.MethodType.methodType(void.class,MovementContext.class,BlockPos.class,DeployerFakePlayer.class,modeClass)).invokeWithArguments(new DeployerMovementBehaviour(),context,pos,fake,Enum.valueOf((Class)modeClass,"USE"));}catch(Throwable failure){throw new IllegalStateException("Native creative supply then placement",failure);}
                    }
                    if(mode.startsWith("world-"))world.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
                    if(mode.startsWith("entity-")){CreateDeployerTransaction.entityBefore(target);target.setCustomName(Component.literal("mixed creative target"));}
                    row=session.finish(!mode.equals("failure"));
                }finally{if(nested!=null)nested.finish(true);}
                if(mode.equals("noop")){check(row==null,"creative no-op invented journal row");tested++;continue;}
                check(row!=null,"creative deployer IO not captured");row.materializeSnapshots();var b=NbtSerde.fromSnbt(row.beBefore);var a=NbtSerde.fromSnbt(row.beAfter);
                check(row.actorUuid==null&&"SYSTEM".equals(row.actorName),"creative deployer blamed owner");
                if(mode.equals("failure")||mode.equals("external-item")||mode.startsWith("nested-")){check(!a.getBoolean("Complete"),"failed/unsupported creative hand IO enabled undo");reject(()->CreateTransferRollbackPlan.prepare(b,a),"partial/unsupported creative hand IO accepted");tested++;continue;}
                check(a.getBoolean("Complete"),"creative deployer capture incomplete: "+kind+"/"+exchange+"/"+mode+" "+a);
                var io=a.getList("Cargo",10).stream().map(t->(CompoundTag)t).filter(t->t.getString("Kind").equals("storage_io")).findFirst().orElseThrow();
                if(mode.startsWith("place"))check(world.getBlockState(pos).is(Blocks.STONE)&&fake.getMainHandItem().isEmpty()&&!a.getList("Uses",10).isEmpty(),"creative supply was not consumed by native placement");
                if(mode.equals("place-missing-use")){for(var tag:List.of(b,a)){tag.put("Uses",new ListTag());tag.putInt("UseCount",0);}reject(()->CreateTransferRollbackPlan.prepare(b,a),"creative conversion lost resource proof");tested++;continue;}
                if(mode.equals("quantity")){io.getCompound("Payload").putInt("count",io.getCompound("Payload").getInt("count")+1);reject(()->CreateTransferRollbackPlan.prepare(b,a),"creative hand imbalance accepted");tested++;continue;}
                if(mode.equals("components")){io.getCompound("Payload").remove("components");reject(()->CreateTransferRollbackPlan.prepare(b,a),"creative hand component mismatch accepted");tested++;continue;}
                if(mode.equals("missing-io")){int index=a.getList("Cargo",10).indexOf(io);a.getList("Cargo",10).remove(index);b.getList("Cargo",10).remove(index);reject(()->CreateTransferRollbackPlan.prepare(b,a),"missing creative hand IO accepted");tested++;continue;}
                if(mode.equals("missing-finite")){int index=-1;for(int i=0;i<a.getList("Cargo",10).size();i++)if(a.getList("Cargo",10).getCompound(i).getString("Kind").equals("item")){index=i;break;}check(index>=0,"mixed return missed finite delta");a.getList("Cargo",10).remove(index);b.getList("Cargo",10).remove(index);reject(()->CreateTransferRollbackPlan.prepare(b,a),"missing finite hand delta accepted");tested++;continue;}
                if(mode.equals("missing-hand")){a.getCompound("Hand").put("Inventory",b.getCompound("Hand").getList("Inventory",10).copy());reject(()->CreateTransferRollbackPlan.prepare(b,a),"missing creative hand delta accepted");tested++;continue;}
                if(mode.equals("missing-phase")){for(var tag:List.of(b,a))for(var raw:tag.getList("Cargo",10))((CompoundTag)raw).remove("Operation");reject(()->CreateTransferRollbackPlan.prepare(b,a),"unknown creative hand phase accepted");tested++;continue;}
                if(mode.equals("missing-header")){b.remove("CreativeIO");a.remove("CreativeIO");reject(()->CreateTransferRollbackPlan.prepare(b,a),"missing creative completeness marker accepted");tested++;continue;}
                if(mode.equals("old-format")){b.putInt("JointFormat",1);a.putInt("JointFormat",1);b.remove("CreativeIO");a.remove("CreativeIO");reject(()->CreateTransferRollbackPlan.prepare(b,a),"creative IO silently accepted as old format");tested++;continue;}
                if(mode.equals("wrong-owner")){io.putUUID("Construction",UUID.randomUUID());reject(()->CreateTransferRollbackPlan.prepare(b,a),"foreign creative hand source accepted");tested++;continue;}
                var plan=CreateTransferRollbackPlan.prepare(b,a);check(plan.hands().size()==1&&plan.creative().size()==(inbound?3:1),"creative hand plan lost virtual/hand endpoints");
                if(mixed)check(plan.cargo().size()==1,"mixed finite+creative return omitted finite cargo");else check(plan.cargo().isEmpty(),"creative hand invented finite cargo delta");
                var details=CartLogDetails.describe(world,row);check(details.stream().anyMatch(c->c.getString().contains(inbound?"Бесконечное хранилище · поглощено":"Бесконечное хранилище · выдано")),"creative hand GUI omitted actual IO");
                check(details.stream().anyMatch(c->c.getString().equals("Груз: изменённых ячеек "+(mixed?1:0))),"creative template incorrectly counted as finite cargo delta");
                if(!inbound&&!mode.startsWith("place"))check(fake.getMainHandItem().getCount()==1&&fake.getMainHandItem().getHoverName().getString().equals("creative deployer supplied component"),"native creative grab lost supplied components");
                else check(fake.getInventory().getItem(3).isEmpty()&&fake.getInventory().getItem(37).isEmpty()&&fake.getInventory().getItem(40).isEmpty(),"native creative void did not clear actual inventory slots");
                var finiteAfter=new ArrayList<ItemStack>();if(mixed)for(int slot=0;slot<finite.getSlots();slot++)finiteAfter.add(finite.getStackInSlot(slot).copy());
                if(mode.equals("world-conflict")||mode.equals("place-world-conflict"))world.setBlock(pos,Blocks.DIRT.defaultBlockState(),3);
                if(mode.equals("entity-conflict"))target.setCustomName(Component.literal("changed target"));
                if(mode.equals("hand-conflict"))fake.getInventory().setItem(4,new ItemStack(Items.BARRIER));
                if(mode.equals("source-conflict"))crate.getStackInSlot(0).set(DataComponents.CUSTOM_NAME,Component.literal("foreign creative template change"));
                if(mode.equals("finite-conflict"))finite.setStackInSlot(26,new ItemStack(Items.BARRIER));
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);
                var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);var step=method(type,"step",net.minecraft.server.MinecraftServer.class);var cancel=method(type,"cancel",String.class);var applied=field(type,"applied");
                boolean canceled=false,rejected=false,locked=false,late=false;boolean prior=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    for(int calls=0;;calls++){
                        check(calls<200,"creative hand rollback did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException failure){
                            if(mode.startsWith("late-")){check(applied.getInt(job)>0,"late creative conflict not exercised after finite writes");cancel.invoke(job,"late source conflict");canceled=true;continue;}
                            if(!mode.endsWith("conflict"))throw new IllegalStateException(kind+"/"+exchange+"/"+mode,failure.getCause());check(applied.getInt(job)==0,"creative hand preflight conflict wrote endpoint");rejected=true;break;
                        }
                        int total=plan.hands().size()+plan.cargo().size()+plan.creative().size()+plan.blocks().size()+plan.targets().size();
                        if(mode.contains("cancel")&&!canceled&&applied.getInt(job)>=(mode.endsWith("all")?total:1)){cancel.invoke(job,"creative hand cancellation");canceled=true;}
                        if(mode.startsWith("late-")&&!late&&applied.getInt(job)>=(mode.equals("late-verify-source-conflict")?total:1)){crate.getStackInSlot(0).set(DataComponents.CUSTOM_NAME,Component.literal("late creative template change"));late=true;}
                        if(mode.equals("locks")&&!locked&&applied.getInt(job)>0){CartAuditContext.restoring(false);try{
                            fake.getInventory().setItem(4,new ItemStack(Items.BARRIER));check(!fake.getInventory().getItem(4).is(Items.BARRIER),"reserved fake inventory changed");
                            check(crate.extractItem(0,1,false).isEmpty()&&crate.insertItem(1,new ItemStack(Items.STONE),false).getCount()==1,"reserved creative source permitted IO");
                            if(mixed)check(finite.extractItem(26,1,false).isEmpty(),"reserved finite cargo changed");
                        }finally{CartAuditContext.restoring(true);}locked=true;}
                        if(done)break;
                    }
                    if(mode.endsWith("conflict")&&!mode.startsWith("late-"))check(rejected,"live creative hand conflict accepted");
                    else{
                        check(canceled||field(type,"committed").getBoolean(job),"creative hand job did not commit");
                        check(CreateDeployerTransaction.hand(context,fake).equals(canceled?a.getCompound("Hand"):beforeHand),"creative hand undo/compensation lost slots, count, components or runtime");
                        if(mixed)for(int slot=0;slot<finite.getSlots();slot++)check(ItemStack.matches((canceled?finiteAfter:finiteBefore).get(slot),finite.getStackInSlot(slot)),"mixed creative hand undo/compensation lost finite cargo");
                        if(mode.startsWith("place")){
                        var modeClass=Class.forName("com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity$Mode");
                        try{java.lang.invoke.MethodHandles.publicLookup().findVirtual(DeployerMovementBehaviour.class,"activate",java.lang.invoke.MethodType.methodType(void.class,MovementContext.class,BlockPos.class,DeployerFakePlayer.class,modeClass)).invokeWithArguments(new DeployerMovementBehaviour(),context,pos,fake,Enum.valueOf((Class)modeClass,"USE"));}catch(Throwable failure){throw new IllegalStateException("Native creative supply then placement",failure);}
                    }
                    if(mode.startsWith("world-")||mode.startsWith("place"))check(world.getBlockState(pos).is(canceled?Blocks.STONE:Blocks.AIR),"mixed creative world undo/compensation failed");
                        if(mode.startsWith("entity-"))check(canceled?target.hasCustomName():!target.hasCustomName(),"mixed creative entity undo/compensation failed");
                        if(!late)check(ItemStack.matches(template,crate.getStackInSlot(0)),"creative hand undo rewrote template");
                        if(mode.equals("locks"))check(locked,"creative hand reservations not tested");
                        if(mode.startsWith("late-"))check(late&&canceled,"late source failure did not compensate");
                    }
                }finally{CartAuditContext.restoring(prior);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
                tested++;
            }finally{boolean prior=CartAuditContext.restoring(true);try{if(target!=null)target.discard();((CartEntityAccess)body).avilixlogger$discardForRollback();world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);world.setBlock(pos.east(4),Blocks.AIR.defaultBlockState(),3);}finally{CartAuditContext.restoring(prior);CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}}
        }
        System.out.println("CREATE_DEPLOYER_CREATIVE_NATIVE_OK: "+tested+" scenarios; four kinds, native take/return with creative supply/void and mixed finite cargo, all fake inventory slots/components/oversized count, exact balance, compensated cancellation and late source failure, early conflicts, reservations, no-op and incomplete rejection");
    }
    private static ItemStack named(Item item,int count,String name){var stack=new ItemStack(item,count);stack.set(DataComponents.CUSTOM_NAME,Component.literal(name));return stack;}
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String message){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
