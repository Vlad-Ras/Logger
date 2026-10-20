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
    private final AbstractContraptionEntity entity;
    private final BlockPos local;
    private final Object handler;
    private final boolean item;
    CreateCreativeRestore(MinecraftServer server,CreateCreativeTransferPlan.IO io){
        address=io.address();local=BlockPos.of(address.getLong("LocalPos"));item=address.getString("ResourceKind").equals("item");
        var level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(address.getString("Dimension"))));
        if(level==null||!(level.getEntity(address.getUUID("StorageEntity")) instanceof AbstractContraptionEntity body)||body.isRemoved()||body.getContraption()==null)throw new IllegalStateException("Creative-источник выгружен или удалён");
        entity=body;handler=resolve();verifyAfter();
        if(item){var payload=NbtSerde.readItemStack(address.getCompound("Payload"),server.registryAccess());if(payload.isEmpty()||payload.getCount()!=address.getCompound("Payload").getInt("count"))throw new IllegalStateException("Не прочитан полный creative предмет");}
        else{var payload=CartRollbackCoordinator.readAutomationFluid(address.getCompound("Payload"),server.registryAccess());if(payload.isEmpty())throw new IllegalStateException("Не прочитана creative жидкость");}
        CartAuditContext.LOCKED_ENTITIES.add(body.getUUID());CartRestoreLocks.construction(address.getUUID("Construction"));CartRestoreLocks.handler(handler);CartRestoreLocks.keepLoaded(level,body.blockPosition());CartStorageAudit.lockMenus(address.getUUID("Construction"),server);
        if(body.getVehicle()!=null)CartAuditContext.LOCKED_ENTITIES.add(body.getVehicle().getUUID());
    }
    private Object resolve(){if(entity.getContraption()==null)return null;return item?entity.getContraption().getStorage().getAllItemStorages().get(local):entity.getContraption().getStorage().getFluids().storages.get(local);}
    public void verifyAfter(){
        if(entity.isRemoved()||entity.getContraption()==null||!entity.level().hasChunkAt(entity.blockPosition())||!CreateContraptionSources.id(entity).equals(address.getUUID("Construction"))||!CreateContraptionSources.kind(entity).name().equals(address.getString("ConstructionKind"))||resolve()!=handler||handler==null||!handler.getClass().getName().equals(address.getString("Handler")))throw new IllegalStateException("Creative-источник или конструкция заменены");
        if(entity instanceof CarriageContraptionEntity carriage&&(carriage.getCarriage()==null||carriage.getCarriage().presentInMultipleDimensions()||!address.hasUUID("Carriage")||!address.getUUID("Carriage").equals(((ContraptionIdentity)carriage.getCarriage()).avilixlogger$identity())))throw new IllegalStateException("Creative-вагон заменён или находится в портале");
        if(item){var h=(IItemHandler)handler;var expected=NbtSerde.readItemStack(address.getCompound("Supplied"),entity.registryAccess());if(h.getSlots()!=address.getInt("Size")||h.getSlotLimit(address.getInt("Slot"))!=address.getInt("Limit")||!ItemStack.matches(expected,h.getStackInSlot(0)))throw new IllegalStateException("Creative-шаблон предмета изменился");}
        else{var h=(IFluidHandler)handler;var expected=address.getCompound("Supplied").isEmpty()?FluidStack.EMPTY:CartRollbackCoordinator.readAutomationFluid(address.getCompound("Supplied"),entity.registryAccess());if(h.getTanks()!=1||h.getTankCapacity(0)!=address.getInt("Limit")||!FluidStack.matches(expected,h.getFluidInTank(0)))throw new IllegalStateException("Creative-шаблон/ёмкость жидкости изменились");}
    }
    public void verifyBefore(){verifyAfter();}
    public void apply(){verifyAfter();}
    public void compensate(){} // This endpoint never writes; compensate the finite participants only.
}
