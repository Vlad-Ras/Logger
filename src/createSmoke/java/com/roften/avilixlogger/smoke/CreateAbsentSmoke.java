package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.CreateContraptionKind;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** No Create imports: checks the optional-mod startup path without an authorization bypass. */
@EventBusSubscriber(modid = "avilixlogger")
public final class CreateAbsentSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("avilixlogger.createAbsentSmoke")) return;
        if (ModList.get().isLoaded("create")) throw new AssertionError("Create unexpectedly present");
        if(com.roften.avilixlogger.core.CreateSupport.available() || com.roften.avilixlogger.core.CartAutomationHooks.installed())throw new AssertionError("Absent Create was enabled");
        if (CreateContraptionKind.fromType("othermod:bearing") != CreateContraptionKind.UNKNOWN)
            throw new AssertionError("Absent integration guessed a foreign type");
        exerciseHopper(event.getServer());
        System.out.println("CREATE_ABSENT_NATIVE_OK: Logger starts without Create, Ponder or Flywheel");
        event.getServer().halt(false);
    }
    private static void exerciseHopper(net.minecraft.server.MinecraftServer server){
        var world=server.overworld();var pos=world.getSharedSpawnPos().above(16);
        var state=net.minecraft.world.level.block.Blocks.HOPPER.defaultBlockState().setValue(net.minecraft.world.level.block.HopperBlock.FACING,net.minecraft.core.Direction.EAST);
        world.setBlock(pos,state,3);var hopper=(net.minecraft.world.level.block.entity.HopperBlockEntity)world.getBlockEntity(pos);
        var cart=new net.minecraft.world.entity.vehicle.MinecartChest(world,pos.getX()+.5,pos.getY()+1.5,pos.getZ()+.5);
        try{
            var field=net.minecraft.server.level.ServerLevel.class.getDeclaredField("entityManager");field.setAccessible(true);
            @SuppressWarnings("unchecked")var manager=(net.minecraft.world.level.entity.PersistentEntitySectionManager<net.minecraft.world.entity.Entity>)field.get(world);
            if(!world.addFreshEntity(cart))throw new AssertionError("Absent base cart spawn");
            manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(cart.blockPosition()),net.minecraft.world.level.entity.Visibility.TRACKED);
            cart.setItem(0,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,2));
            var take=net.minecraft.world.level.block.entity.HopperBlockEntity.class.getDeclaredMethod("tryTakeInItemFromSlot",net.minecraft.world.level.block.entity.Hopper.class,net.minecraft.world.Container.class,int.class,net.minecraft.core.Direction.class);take.setAccessible(true);
            if(!(Boolean)take.invoke(null,hopper,cart,0,net.minecraft.core.Direction.DOWN)||!net.minecraft.world.level.block.entity.HopperBlockEntity.suckInItems(world,hopper)||!cart.getItem(0).isEmpty()||hopper.getItem(0).getCount()!=2)throw new AssertionError("Absent native extraction failed");
            cart.setPos(pos.getX()+1.5,pos.getY()+.5,pos.getZ()+.5);
            manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(cart.blockPosition()),net.minecraft.world.level.entity.Visibility.TRACKED);
            var eject=net.minecraft.world.level.block.entity.HopperBlockEntity.class.getDeclaredMethod("ejectItems",net.minecraft.world.level.Level.class,net.minecraft.core.BlockPos.class,net.minecraft.world.level.block.entity.HopperBlockEntity.class);eject.setAccessible(true);
            if(!(Boolean)eject.invoke(null,world,pos,hopper)||cart.getItem(0).getCount()!=1||hopper.getItem(0).getCount()!=1)throw new AssertionError("Absent NeoForge native insertion failed");
            var capability=net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.ENTITY_AUTOMATION;
            var providersField=net.neoforged.neoforge.capabilities.EntityCapability.class.getDeclaredField("providers");providersField.setAccessible(true);
            @SuppressWarnings("unchecked")var providers=(java.util.Map<Object,Object>)providersField.get(capability);
            boolean present=providers.containsKey(cart.getType());var previous=providers.remove(cart.getType());
            try{if(!(Boolean)eject.invoke(null,world,pos,hopper)||cart.getItem(0).getCount()!=2||!hopper.getItem(0).isEmpty())throw new AssertionError("Absent vanilla native insertion failed");}
            finally{if(present)providers.put(cart.getType(),previous);}
            cart.discard();manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(pos),net.minecraft.world.level.entity.Visibility.HIDDEN);manager.updateChunkStatus(new net.minecraft.world.level.ChunkPos(pos.east()),net.minecraft.world.level.entity.Visibility.HIDDEN);
            System.out.println("CREATE_ABSENT_HOPPER_NATIVE_OK: both directions and both paths");
        }catch(ReflectiveOperationException error){throw new AssertionError("Absent hopper exchange failed",error);}
        finally{cart.discard();world.setBlock(pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),3);}
    }
}
