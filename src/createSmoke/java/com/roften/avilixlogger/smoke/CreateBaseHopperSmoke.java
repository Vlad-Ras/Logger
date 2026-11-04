package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.*;
import net.minecraft.world.level.entity.*;
import net.neoforged.neoforge.capabilities.*;
import java.lang.reflect.InvocationTargetException;
import java.util.*;

/** Detached native fixtures; no production authorization bypass. */
public final class CreateBaseHopperSmoke {
    public static void run(MinecraftServer server,CompoundTag checkpoint)throws Exception{
        for(boolean chest:List.of(true,false))for(boolean vanilla:List.of(true,false))for(boolean load:List.of(false,true)){
            var modes=new ArrayList<>(List.of("empty","full-one","full-many","one","merge","locked-source","locked-recipient","failure","ordinary"));
            if(load)modes.addAll(List.of("last-slot","refused"));
            for(String mode:modes)exercise(server,checkpoint,chest,vanilla,load,mode);
        }
        System.out.println("CREATE_BASE_HOPPER_NATIVE_OK: 36 scenarios; restored extraction, components, net no-op, locks, partial exceptions and unrelated cart");
        System.out.println("CREATE_BASE_HOPPER_LOAD_NATIVE_OK: 44 scenarios; native insertion, both base types and paths, source/destination slots, components, refusal, locks and partial failure");
    }
    /** Temporarily remove only this entity type's capability providers to exercise vanilla fallback. */
    public static boolean move(ServerLevel world,HopperBlockEntity hopper,Container base,boolean load,boolean vanilla)throws Exception{
        if(!load){
            if(!vanilla)return HopperBlockEntity.suckInItems(world,hopper);
            var take=HopperBlockEntity.class.getDeclaredMethod("tryTakeInItemFromSlot",Hopper.class,Container.class,int.class,Direction.class);take.setAccessible(true);
            return (Boolean)take.invoke(null,hopper,base,0,Direction.DOWN);
        }
        var field=EntityCapability.class.getDeclaredField("providers");field.setAccessible(true);
        @SuppressWarnings("unchecked")var providers=(Map<Object,Object>)field.get(Capabilities.ItemHandler.ENTITY_AUTOMATION);
        var type=((Entity)base).getType();boolean present=providers.containsKey(type);Object previous=vanilla?providers.remove(type):null;
        try{
            if(vanilla)check(((Entity)base).getCapability(Capabilities.ItemHandler.ENTITY_AUTOMATION,Direction.WEST)==null,"vanilla fallback fixture still has capability");
            var eject=HopperBlockEntity.class.getDeclaredMethod("ejectItems",net.minecraft.world.level.Level.class,BlockPos.class,HopperBlockEntity.class);eject.setAccessible(true);
            return (Boolean)eject.invoke(null,world,hopper.getBlockPos(),hopper);
        }finally{if(vanilla){if(present)providers.put(type,previous);else providers.remove(type);}}
    }
    private static void exercise(MinecraftServer server,CompoundTag checkpoint,boolean chest,boolean vanilla,boolean load,String mode)throws Exception{
        var world=server.overworld();var pos=world.getSharedSpawnPos().above(18).offset(12,0,0);
        var staged=new StagedCartRestore(checkpoint);while(!staged.step(world)){}
        var entity=staged.entity;staged.cart.discard();
        double x=pos.getX()+.5+(load?1:0),y=pos.getY()+(load?.5:1.5),z=pos.getZ()+.5;
        AbstractMinecart cart;
        if(load&&(mode.equals("failure")||mode.equals("refused"))){
            cart=chest?new MinecartChest(world,x,y,z){
                @Override public void setItem(int slot,ItemStack value){super.setItem(slot,value);if(mode.equals("failure")&&!value.isEmpty())throw new IllegalArgumentException("fixture base failure after write");}
                @Override public boolean canPlaceItem(int slot,ItemStack value){return !mode.equals("refused")&&super.canPlaceItem(slot,value);}
            }:new MinecartHopper(world,x,y,z){
                @Override public void setItem(int slot,ItemStack value){super.setItem(slot,value);if(mode.equals("failure")&&!value.isEmpty())throw new IllegalArgumentException("fixture base failure after write");}
                @Override public boolean canPlaceItem(int slot,ItemStack value){return !mode.equals("refused")&&super.canPlaceItem(slot,value);}
            };
        }else cart=chest?new MinecartChest(world,x,y,z):new MinecartHopper(world,x,y,z);
        var inventory=(Container)cart;
        int count=mode.equals("empty")?0:mode.equals("one")||mode.equals("full-one")?1:12;
        var item=new ItemStack(Items.DIAMOND,count);item.set(DataComponents.CUSTOM_NAME,Component.literal("Base cargo"));
        if(!load)inventory.setItem(0,item.copy());
        entity.setUUID(UUID.randomUUID());entity.setPos(cart.position());entity.startRiding(cart,true);
        check(world.addFreshEntity(cart)&&world.addFreshEntity(entity),"base capture source spawn");CreateCartAudit.attach(entity);CreateCartAudit.state(entity).locked=false;CartStorageAudit.bind(entity);
        if(mode.equals("ordinary"))entity.stopRiding();
        var field=ServerLevel.class.getDeclaredField("entityManager");field.setAccessible(true);
        @SuppressWarnings("unchecked")var manager=(PersistentEntitySectionManager<Entity>)field.get(world);
        manager.updateChunkStatus(new ChunkPos(cart.blockPosition()),Visibility.TRACKED);
        world.setBlock(pos,Blocks.HOPPER.defaultBlockState().setValue(HopperBlock.FACING,Direction.EAST),3);
        var hopper=(HopperBlockEntity)world.getBlockEntity(pos);int sourceSlot=load&&mode.equals("last-slot")?4:0;
        if(load)hopper.setItem(sourceSlot,item.copy());
        Container destination=load?inventory:hopper;
        if(mode.startsWith("full"))for(int i=0;i<destination.getContainerSize();i++)destination.setItem(i,new ItemStack(Items.STONE,64));
        if(mode.equals("merge"))destination.setItem(0,item.copyWithCount(63));
        if(load&&mode.equals("last-slot"))for(int i=0;i<inventory.getContainerSize()-1;i++)inventory.setItem(i,new ItemStack(Items.STONE,64));
        if(!load&&mode.equals("failure")){
            hopper=new HopperBlockEntity(pos,world.getBlockState(pos)){
                @Override public void setItem(int slot,ItemStack value){super.setItem(slot,value);throw new IllegalArgumentException("fixture hopper failure after write");}
            };world.setBlockEntity(hopper);destination=hopper;
        }
        if(mode.equals(load?"locked-recipient":"locked-source"))CreateCartAudit.state(entity).locked=true;
        if(mode.equals(load?"locked-source":"locked-recipient")){CartAuditContext.rollbackActive=true;CartRestoreLocks.container(hopper);CartRestoreLocks.block(world,pos);}
        try(var capture=CreateCargoAudit.captureChanges(entity)){
            boolean moved=false,failed=false;
            try{moved=move(world,hopper,inventory,load,vanilla);}
            catch(InvocationTargetException error){if(!(error.getCause() instanceof IllegalArgumentException))throw error;failed=true;}
            catch(IllegalArgumentException error){failed=true;}
            var rows=List.copyOf(capture.rows());for(var row:rows)row.materializeSnapshots();
            var source=load?hopper:inventory;var remaining=source.getItem(sourceSlot);
            boolean noChange=mode.equals("empty")||mode.startsWith("full")||mode.startsWith("locked")||mode.equals("refused");
            if(noChange)check(!moved&&!failed&&rows.isEmpty()&&ItemStack.matches(item,remaining),"refused/empty transfer changed or recorded cargo: "+mode+"/"+load+"/"+vanilla);
            else if(mode.equals("ordinary"))check(moved&&rows.isEmpty()&&remaining.getCount()==count-1,"ordinary cart linked to construction");
            else{
                check(rows.size()==2&&failed==mode.equals("failure")&&remaining.getCount()==count-1,"native capture missing/duplicated: "+mode+"/"+load+"/"+vanilla+" rows="+rows.size());
                var before=NbtSerde.fromSnbt(rows.getFirst().beBefore);var after=NbtSerde.fromSnbt(rows.getFirst().beAfter);
                var manifestBefore=NbtSerde.fromSnbt(rows.getLast().beBefore);var manifest=NbtSerde.fromSnbt(rows.getLast().beAfter);
                int destinationSlot=mode.equals("last-slot")?inventory.getContainerSize()-1:0;
                check(after.getBoolean("BaseCart")&&after.getInt("Slot")== (load?destinationSlot:0)&&after.getUUID("Transfer").equals(manifest.getUUID("Transfer")),"base slot/transfer lost");
                var slots=manifest.getList("Slots",10);check(slots.size()==1&&slots.getCompound(0).getInt("Slot")== (load?sourceSlot:0)&&slots.getCompound(0).getCompound("Item").getInt("count")== (load?count-1:mode.equals("merge")?64:1),"hopper result slot/count missing");
                check(ItemStack.isSameItemSameComponents(item,destination.getItem(destinationSlot)),"native components lost");
                var plan=new CartAutomationTransferPlan();plan.cargo(before,after);boolean denied=false;
                try{plan.manifest(manifestBefore,manifest);check(plan.finish(CreateCartAudit.state(entity).id).size()==1,"transfer plan lost hopper");}catch(IllegalStateException expected){denied=true;}
                check(denied==mode.equals("failure")&&manifest.getBoolean("Complete")==!denied,"partial failure accepted for rollback");
                check(CartLogDetails.describe(world,rows.getFirst()).stream().anyMatch(c->c.getString().contains("До:")),"base details missing");
            }
            check(!CartAutomationAudit.baseHopperCovered(cart),"base snapshot suppression leaked");
        }finally{
            CartAuditContext.rollbackActive=false;CartRestoreLocks.clear();CreateCartAudit.state(entity).locked=false;
            ((CartEntityAccess)entity).avilixlogger$discardForRollback();cart.discard();world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
            manager.updateChunkStatus(new ChunkPos(cart.blockPosition()),Visibility.HIDDEN);CartAutomationAudit.clear();
        }
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
