package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;

/** Read-only endpoint: freeze the exact creative template while restoring the actual recipient/sender. */
final class CreateCreativeRestore implements CreateTransferRollbackCoordinator.Endpoint {
    private final net.minecraft.nbt.CompoundTag address;
    private final CreateCargoLocation location;
    private final net.minecraft.core.HolderLookup.Provider registries;
    private final BlockPos local;
    private final Object handler;
    private final boolean item;
    CreateCreativeRestore(MinecraftServer server,CreateCreativeTransferPlan.IO io){
        address=io.address();local=BlockPos.of(address.getLong("LocalPos"));item=address.getString("ResourceKind").equals("item");
        registries=server.registryAccess();location=new CreateCargoLocation(server,address);handler=resolve();verifyAfter();
        if(item){var payload=NbtSerde.readItemStack(address.getCompound("Payload"),server.registryAccess());if(payload.isEmpty()||payload.getCount()!=address.getCompound("Payload").getInt("count"))throw new IllegalStateException("Не прочитан полный creative предмет");}
        else{var payload=CartRollbackCoordinator.readAutomationFluid(address.getCompound("Payload"),server.registryAccess());if(payload.isEmpty())throw new IllegalStateException("Не прочитана creative жидкость");}
        location.reserve(server);CartRestoreLocks.handler(handler);

    }
    private Object resolve(){return location.resolve(local,item,false);}
    public void verifyAfter(){
        location.verify();if(resolve()!=handler||handler==null||!handler.getClass().getName().equals(address.getString("Handler")))throw new IllegalStateException("Creative-хранилище заменено");
        if(item){var h=(IItemHandler)handler;var expected=NbtSerde.readItemStack(address.getCompound("Supplied"),registries);if(h.getSlots()!=address.getInt("Size")||h.getSlotLimit(address.getInt("Slot"))!=address.getInt("Limit")||!ItemStack.matches(expected,h.getStackInSlot(0)))throw new IllegalStateException("Creative-шаблон предмета изменился");}
        else{var h=(IFluidHandler)handler;var expected=address.getCompound("Supplied").isEmpty()?FluidStack.EMPTY:CartRollbackCoordinator.readAutomationFluid(address.getCompound("Supplied"),registries);if(h.getTanks()!=1||h.getTankCapacity(0)!=address.getInt("Limit")||!FluidStack.matches(expected,h.getFluidInTank(0)))throw new IllegalStateException("Creative-шаблон/ёмкость жидкости изменились");}
    }
    public void verifyBefore(){verifyAfter();}
    public void apply(){verifyAfter();}
    public void compensate(){} // This endpoint never writes; compensate the finite participants only.
}
