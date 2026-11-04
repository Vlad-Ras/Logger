package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.*;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import java.lang.reflect.*;
import java.util.*;

/** Native creative IO, exact finite peers and the production compensated coordinator. */
public final class CreateCreativeTransferSmoke {
    @net.neoforged.fml.common.EventBusSubscriber(modid="avilixlogger") public static final class Probe {
        @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent e){
            if(!Boolean.getBoolean("avilixlogger.creativeTransferSmoke"))return;
            try{run(e.getServer().overworld());}catch(Throwable failure){failure.printStackTrace();System.out.println("CREATE_CREATIVE_TRANSFER_FAILED");}finally{e.getServer().halt(false);}
        }
    }
    public static void run(ServerLevel world)throws Exception{
        var factory=method(CreateCargoSmoke.class,"entity",ServerLevel.class,CreateContraptionKind.class,boolean.class);
        var attach=method(CreateLinkedTransferSmoke.class,"attachCarriage",AbstractContraptionEntity.class);
        var pos=world.getSharedSpawnPos().offset(42,42,42);method(CreateFunnelSmoke.class,"visible",ServerLevel.class,BlockPos.class).invoke(null,world,pos);
        int tested=0;
        for(var kind:List.of(CreateContraptionKind.MINECART,CreateContraptionKind.TRAIN,CreateContraptionKind.BEARING,CreateContraptionKind.OTHER))
        for(boolean mounted:new boolean[]{false,true})for(boolean fluid:new boolean[]{false,true})for(boolean inbound:new boolean[]{false,true})
        for(String mode:List.of("undo","cancel-first","cancel-all","peer-conflict","source-conflict","locks","quantity-conflict","component-conflict","missing-peer","failure")){
            var body=(AbstractContraptionEntity)factory.invoke(null,world,kind,true);attach.invoke(null,body);
            body.setPos(pos.above(5).getX(),pos.above(5).getY(),pos.above(5).getZ());check(world.addFreshEntity(body),"creative construction spawn");
            try{
                var supplied=new ItemStack(Items.EMERALD);supplied.set(DataComponents.CUSTOM_NAME,Component.literal("creative template components"));
                var local=new BlockPos(4,0,0);var fluidLocal=new BlockPos(4,0,1);
                replace(body,local,new CreativeCrateMountedStorage(supplied));
                CartStorageAudit.bind(body);
                var crate=body.getContraption().getStorage().getAllItemStorages().get(local);
                var infinite=body.getContraption().getStorage().getFluids().storages.get(fluidLocal);
                world.setBlock(pos,AllBlocks.ANDESITE_FUNNEL.getDefaultState(),3);
                world.setBlock(pos.east(2),fluid?AllBlocks.FLUID_TANK.getDefaultState():Blocks.CHEST.defaultBlockState(),3);
                var itemCache=fluid?null:BlockCapabilityCache.create(Capabilities.ItemHandler.BLOCK,world,pos.east(2),null);
                var fluidCache=fluid?BlockCapabilityCache.create(Capabilities.FluidHandler.BLOCK,world,pos.east(2),null):null;
                var peerItem=fluid?null:mounted?body.getContraption().getStorage().getAllItemStorages().get(BlockPos.ZERO):itemCache.getCapability();
                var peerFluid=fluid?mounted?body.getContraption().getStorage().getFluids().storages.get(new BlockPos(1,0,1)):fluidCache.getCapability():null;
                // Void deliberately accepts a different resource from the creative supply template.
                var input=new ItemStack(Items.DIAMOND,5);input.set(DataComponents.CUSTOM_NAME,Component.literal("consumed components"));
                if(inbound){if(fluid)peerFluid.fill(new FluidStack(Fluids.WATER,300),IFluidHandler.FluidAction.EXECUTE);else check(peerItem.insertItem(0,input,false).isEmpty(),"seed finite sender");}
                var beforeItem=fluid?ItemStack.EMPTY:peerItem.getStackInSlot(0).copy();
                var beforeFluid=fluid?peerFluid.getFluidInTank(0).copy():FluidStack.EMPTY;
                var templateItem=crate.getStackInSlot(0).copy();var templateFluid=infinite.getFluidInTank(0).copy();
                List<LogEntry> rows;var scope=CartAutomationAudit.captureChanges(world.getBlockEntity(pos));
                try{
                    if(!mounted){if(fluid)fluidCache.getCapability();else itemCache.getCapability();}
                    if(fluid){if(inbound)check(infinite.fill(peerFluid.drain(100,IFluidHandler.FluidAction.EXECUTE),IFluidHandler.FluidAction.EXECUTE)==100,"native void");else check(peerFluid.fill(infinite.drain(100,IFluidHandler.FluidAction.EXECUTE),IFluidHandler.FluidAction.EXECUTE)==100,"native supply");}
                    else{if(inbound)check(crate.insertItem(1,peerItem.extractItem(0,2,false),false).isEmpty(),"native item void");else check(peerItem.insertItem(0,crate.extractItem(0,2,false),false).isEmpty(),"native item supply");}
                    rows=scope.snapshotRows(!mode.equals("failure"));
                }finally{scope.finish(!mode.equals("failure"));}
                check(rows.size()==(mounted?3:2),"creative IO/manifest missing");rows.forEach(LogEntry::materializeSnapshots);
                var b=NbtSerde.fromSnbt(rows.getLast().beBefore);var a=NbtSerde.fromSnbt(rows.getLast().beAfter);
                var virtual=a.getList("Cargo",10).stream().map(t->(CompoundTag)t).filter(t->t.getString("Kind").equals("storage_io")).findFirst().orElseThrow();
                if(mode.equals("failure")){check(!a.getBoolean("Complete"),"failed capture marked complete");reject(()->CreateTransferRollbackPlan.prepare(b,a),"failed creative operation accepted");tested++;continue;}
                check(a.getBoolean("Complete"),"creative capture incomplete: "+kind+"/"+mounted+"/"+fluid+"/"+inbound+"/"+mode+" "+a);
                if(mode.equals("quantity-conflict")){virtual.getCompound("Payload").putInt(fluid?"amount":"count",1);reject(()->CreateTransferRollbackPlan.prepare(b,a),"unbalanced creative quantity accepted");tested++;continue;}
                if(mode.equals("component-conflict")){if(fluid)virtual.getCompound("Payload").putString("id",inbound?"minecraft:lava":"minecraft:water");else virtual.getCompound("Payload").remove("components");reject(()->CreateTransferRollbackPlan.prepare(b,a),"unbalanced creative identity/components accepted");tested++;continue;}
                if(mode.equals("missing-peer")){if(mounted){int index=a.getList("Cargo",10).getCompound(0).getString("Kind").equals("storage_io")?1:0;a.getList("Cargo",10).remove(index);b.getList("Cargo",10).remove(index);a.putInt("Changes",1);b.putInt("Changes",1);a.getList("Cargo",10).getCompound(0).putInt("TransferIndex",0);b.getList("Cargo",10).getCompound(0).putInt("TransferIndex",0);}else{a.getList("Slots",10).clear();b.getList("Slots",10).clear();}reject(()->CreateTransferRollbackPlan.prepare(b,a),"creative missing finite peer accepted");tested++;continue;}
                var plan=CreateTransferRollbackPlan.prepare(b,a);check(plan.creative().size()==1&&plan.external().size()==(mounted?0:1)&&plan.cargo().size()==(mounted?1:0),"creative plan omitted reservation/finite peer");
                var afterItem=fluid?ItemStack.EMPTY:peerItem.getStackInSlot(0).copy();var afterFluid=fluid?peerFluid.getFluidInTank(0).copy():FluidStack.EMPTY;
                if(mode.equals("peer-conflict")){if(fluid)peerFluid.drain(1,IFluidHandler.FluidAction.EXECUTE);else peerItem.extractItem(0,1,false);}
                if(mode.equals("source-conflict")){
                    if(fluid){var data=new CompoundTag();data.putInt("Capacity",8000);data.put("ProvidedStack",new FluidStack(Fluids.WATER,8000).save(world.registryAccess()));replace(body,fluidLocal,com.simibubi.create.content.fluids.tank.storage.creative.CreativeFluidTankMountedStorage.fromLegacy(world.registryAccess(),data));}
                    else replace(body,local,new CreativeCrateMountedStorage(new ItemStack(Items.GOLD_INGOT)));
                }
                var type=Class.forName("com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator$Job");var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CreateTransferRollbackPlan.class,long.class);ctor.setAccessible(true);
                var job=ctor.newInstance(world.getServer().createCommandSourceStack(),plan,1L);var step=method(type,"step",net.minecraft.server.MinecraftServer.class);var cancel=method(type,"cancel",String.class);var applied=field(type,"applied");
                boolean rejected=false,canceled=false,locked=false;boolean prior=CartAuditContext.restoring(true);CartAuditContext.rollbackActive=true;
                try{
                    for(int calls=0;;calls++){
                        check(calls<100,"creative rollback did not terminate");boolean done;
                        try{done=(Boolean)step.invoke(job,world.getServer());}catch(InvocationTargetException failure){if(!mode.endsWith("conflict"))throw failure;check(applied.getInt(job)==0,"creative conflict wrote finite peer");rejected=true;break;}
                        if(mode.startsWith("cancel")&&!canceled&&applied.getInt(job)>=(mode.endsWith("all")?2:1)){cancel.invoke(job,"creative cancellation");canceled=true;}
                        if(mode.equals("locks")&&!locked&&applied.getInt(job)>0){CartAuditContext.restoring(false);try{
                            check(crate.extractItem(0,1,false).isEmpty()&&crate.insertItem(1,new ItemStack(Items.STONE),false).getCount()==1,"reserved creative item permitted IO");
                            check(infinite.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty()&&infinite.fill(new FluidStack(Fluids.WATER,1),IFluidHandler.FluidAction.EXECUTE)==0,"reserved creative fluid permitted IO");
                            if(mounted){if(fluid)check(peerFluid.drain(1,IFluidHandler.FluidAction.EXECUTE).isEmpty(),"reserved finite tank drained");else check(peerItem.extractItem(0,1,false).isEmpty(),"reserved finite cargo extracted");}
                            else check(!world.setBlock(pos.east(2),Blocks.GOLD_BLOCK.defaultBlockState(),3),"reserved finite peer replaced");
                        }finally{CartAuditContext.restoring(true);}locked=true;}
                        if(done)break;
                    }
                    if(mode.endsWith("conflict"))check(rejected,"creative live conflict accepted");
                    else{check(canceled||field(type,"committed").getBoolean(job),"creative transaction did not commit");
                        check(fluid?FluidStack.matches(canceled?afterFluid:beforeFluid,peerFluid.getFluidInTank(0)):ItemStack.matches(canceled?afterItem:beforeItem,peerItem.getStackInSlot(0)),"creative finite peer undo/compensation lost quantity or components");
                        check(ItemStack.matches(templateItem,crate.getStackInSlot(0))&&FluidStack.matches(templateFluid,infinite.getFluidInTank(0)),"creative rollback rewrote infinite template");
                        if(mode.equals("locks"))check(locked,"creative reservations not exercised");}
                }finally{CartAuditContext.restoring(prior);CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}
                tested++;
            }finally{boolean prior=CartAuditContext.restoring(true);try{((CartEntityAccess)body).avilixlogger$discardForRollback();world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);world.setBlock(pos.east(2),Blocks.AIR.defaultBlockState(),3);}finally{CartAuditContext.restoring(prior);CartRestoreLocks.clear();CartAuditContext.LOCKED_ENTITIES.clear();}}
        }
        System.out.println("CREATE_CREATIVE_TRANSFER_NATIVE_OK: "+tested+" scenarios; four kinds, actual item/fluid supply/void with world and mounted finite peers, component and quantity balance, finite peer undo, compensated cancellation, source/peer conflicts, reservations, missing peer and failure rejection");
    }
    private static void replace(AbstractContraptionEntity body,BlockPos local,Object replacement)throws Exception{
        var storage=body.getContraption().getStorage();var items=new LinkedHashMap<>(storage.getAllItemStorages());var fluids=new LinkedHashMap<>(storage.getFluids().storages);
        if(replacement instanceof com.simibubi.create.api.contraption.storage.item.MountedItemStorage item)items.put(local,item);
        else fluids.put(local,(com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage)replacement);
        var access=(CartStorageAccessor)storage;access.avilixlogger$reset();items.forEach((p,h)->access.avilixlogger$addItem(h,p));fluids.forEach((p,h)->access.avilixlogger$addFluid(h,p));
        method(storage.getClass(),"initialize").invoke(storage);
        CartStorageAudit.bind(body);
    }
    private static Method method(Class<?> type,String name,Class<?>...args)throws Exception{var m=type.getDeclaredMethod(name,args);m.setAccessible(true);return m;}
    private static Field field(Class<?> type,String name)throws Exception{var f=type.getDeclaredField(name);f.setAccessible(true);return f;}
    private static void reject(Runnable action,String message){try{action.run();}catch(RuntimeException expected){return;}throw new AssertionError(message);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
