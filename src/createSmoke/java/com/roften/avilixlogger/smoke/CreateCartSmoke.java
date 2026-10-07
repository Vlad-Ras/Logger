package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.OrientedContraptionEntity;
import com.simibubi.create.content.contraptions.mounted.MountedContraption;
import com.simibubi.create.content.contraptions.mounted.MinecartContraptionItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.Minecart;
import net.minecraft.world.entity.vehicle.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Development-only source set; never included in the shipped mod. No AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class CreateCartSmoke {
    @SubscribeEvent
    public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.createSmoke"))return;
        try {
            var world=event.getServer().overworld();
            MountedContraption c=new MountedContraption();c.anchor=BlockPos.ZERO;c.bounds=new net.minecraft.world.phys.AABB(BlockPos.ZERO);
            c.getBlocks().put(BlockPos.ZERO,new StructureBlockInfo(BlockPos.ZERO,Blocks.STONE.defaultBlockState(),null));
            var inventory=new net.neoforged.neoforge.items.ItemStackHandler(9);
            inventory.setStackInSlot(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,12));
            var storage=(com.roften.avilixlogger.compat.create.mixin.CartStorageAccessor)c.getStorage();
            storage.avilixlogger$addItem(new com.simibubi.create.impl.contraption.storage.FallbackMountedStorage(inventory),BlockPos.ZERO);
            var fluid=(net.minecraft.nbt.CompoundTag)new net.neoforged.neoforge.fluids.FluidStack(net.minecraft.world.level.material.Fluids.WATER,1250).save(world.registryAccess());
            fluid.putInt("Capacity",4000);
            storage.avilixlogger$addFluid(com.simibubi.create.content.fluids.tank.storage.FluidTankMountedStorage.fromLegacy(world.registryAccess(),fluid),BlockPos.ZERO);
            var entity=OrientedContraptionEntity.create(world,c,Direction.NORTH);
            var cart=new Minecart(world,0,128,0);entity.setPos(0,128,0);entity.startRiding(cart,true);
            CartAuditState state=((CartAuditAccess)c).avilixlogger$cartState();state.owner=java.util.UUID.randomUUID();state.ownerName="smoke";
            var item=MinecartContraptionItem.create(AbstractMinecart.Type.RIDEABLE,entity);
            check(state.id.equals(CreateCartAudit.itemId(item)),"packed ID");
            var roundTrip=Contraption.fromNBT(world,c.writeNBT(world.registryAccess(),false),false);
            check(state.id.equals(((CartAuditAccess)roundTrip).avilixlogger$cartState().id),"save/load ID");
            var snapshot=CreateCartAudit.snapshot(entity,null);
            var staged=new StagedCartRestore(snapshot);int steps=0;
            while(!staged.step(world))check(++steps<100,"restore did not terminate");
            check(staged.entity.getContraption().getBlocks().size()==1,"restored block count");
            check(staged.entity.getContraption().getBlocks().get(BlockPos.ZERO).state().is(Blocks.STONE),"restored block state");
            check(state.id.equals(CreateCartAudit.state(staged.entity).id),"restored persistent ID");
            var restoredStorage=staged.entity.getContraption().getStorage();
            check(restoredStorage.getAllItemStorages().get(BlockPos.ZERO).getStackInSlot(3).getCount()==12,"restored cargo count");
            check(restoredStorage.getFluids().storages.get(BlockPos.ZERO).getFluidInTank(0).getAmount()==1250,"restored fluid amount");
            check(world.getEntity(staged.entity.getUUID())!=staged.entity,"staging published entity prematurely");
            exerciseRollback(event,entity,cart,false);
            exerciseRollback(event,staged.entity,staged.cart,true);
            System.out.println("AVILIX_CREATE_SMOKE_OK identity=pack+save+load restore=detached+incremental+cargo+fluid world=commit+cancel+32-drops");
        }catch(Throwable failure){
            failure.printStackTrace();System.out.println("AVILIX_CREATE_SMOKE_FAILED");
            event.getServer().halt(false);
        }
    }
    private static void exerciseRollback(ServerStartedEvent event,OrientedContraptionEntity current,AbstractMinecart currentCart,boolean cancel) throws Exception {
        var world=event.getServer().overworld();var pos=world.getSharedSpawnPos().above(5);
        currentCart.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);current.setPos(currentCart.position());
        if(current.getVehicle()!=currentCart)current.startRiding(currentCart,true);
        check(world.addFreshEntity(currentCart),"source cart spawn");check(world.addFreshEntity(current),"source contraption spawn");CreateCartAudit.attach(current);
        var target=CreateCartAudit.snapshot(current,null);
        world.setBlock(pos,Blocks.STONE.defaultBlockState(),3);
        var state=CreateCartAudit.state(current);state.locked=true;
        var undo=new CartRollbackPlan.BlockUndo(world.dimension().location().toString(),pos,"","",net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState()),net.minecraft.nbt.NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState()),null,null,null,null);
        var drops=new java.util.ArrayList<net.minecraft.world.entity.item.ItemEntity>();var effects=new java.util.ArrayList<CartRollbackPlan.SpawnUndo>();
        for(int i=0;i<32;i++){
            var drop=new net.minecraft.world.entity.item.ItemEntity(world,pos.getX()+.5,pos.getY()+2,pos.getZ()+.5,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.COBBLESTONE,i+1));check(world.addFreshEntity(drop),"fixture output spawn");drops.add(drop);
            effects.add(new CartRollbackPlan.SpawnUndo(world.dimension().location().toString(),drop.getUUID(),"minecraft:item",com.roften.avilixlogger.core.NbtSerde.snapshotEntity(world,drop),com.roften.avilixlogger.core.NbtSerde.snapshotItemStack(drop.getItem(),world.registryAccess())));
        }
        var plan=new CartRollbackPlan(state.id,1,2,1,target,target,java.util.List.of(undo),java.util.List.of(),effects,target.sizeInBytes());
        var replacement=new StagedCartRestore(target);
        Class<?> type=Class.forName("com.roften.avilixlogger.compat.create.CartRollbackCoordinator$Job");
        var ctor=type.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,CartRollbackPlan.class,StagedCartRestore.class,net.minecraft.world.entity.Entity.class);ctor.setAccessible(true);
        Object job=ctor.newInstance(event.getServer().createCommandSourceStack(),plan,replacement,current);
        var step=type.getDeclaredMethod("step",net.minecraft.server.MinecraftServer.class);step.setAccessible(true);
        java.lang.reflect.Field phase=type.getDeclaredField("phase"),index=type.getDeclaredField("index"),applied=type.getDeclaredField("applied"),failure=type.getDeclaredField("failure");
        phase.setAccessible(true);index.setAccessible(true);applied.setAccessible(true);failure.setAccessible(true);
        boolean previous=com.roften.avilixlogger.core.CartAuditContext.restoring(true);com.roften.avilixlogger.core.CartAuditContext.rollbackActive=true;
        try {
            int calls=0;
            while(!(Boolean)step.invoke(job,event.getServer())) {
                check(++calls<500,"world rollback did not terminate");
                if(cancel && applied.getInt(job)>0 && phase.getInt(job)==3){phase.setInt(job,4);index.setInt(job,applied.getInt(job)-1);failure.set(job,"fixture cancellation");}
            }
            if(cancel){check(world.getBlockState(pos).is(Blocks.STONE),"cancel did not compensate world");check(!current.isRemoved()&&!currentCart.isRemoved(),"cancel consumed source");check(drops.stream().noneMatch(net.minecraft.world.entity.Entity::isRemoved),"cancel consumed drill output");}
            else {
                check(world.getBlockState(pos).isAir(),"world undo not applied");check(current.isRemoved()&&currentCart.isRemoved(),"commit left source copy");
                var result=CreateCartAudit.loaded(state.id);check(result==replacement.entity,"committed representation not indexed");
                check(replacement.entity.getVehicle()==replacement.cart,"committed cart lost passenger");
                check(drops.stream().allMatch(net.minecraft.world.entity.Entity::isRemoved),"rollback duplicated drill output");
            }
        }finally{
            com.roften.avilixlogger.core.CartRestoreLocks.clear();com.roften.avilixlogger.core.CartAuditContext.rollbackActive=false;com.roften.avilixlogger.core.CartAuditContext.restoring(previous);
            com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.clear();for(var drop:drops)if(!drop.isRemoved())drop.discard();
            if(!current.isRemoved())((CartEntityAccess)current).avilixlogger$discardForRollback();if(!currentCart.isRemoved())currentCart.discard();
            if(replacement.entity!=null&&!replacement.entity.isRemoved())((CartEntityAccess)replacement.entity).avilixlogger$discardForRollback();if(replacement.cart!=null&&!replacement.cart.isRemoved())replacement.cart.discard();
            world.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
    }
    private static void check(boolean pass,String message){if(!pass)throw new AssertionError(message);}
}
