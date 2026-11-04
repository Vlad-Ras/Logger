package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.items.IItemHandler;

/** A read-only participant; finite cargo/endpoints compensate any partial rollback. */
final class CreateWorldCreativeRestore implements CreateTransferRollbackCoordinator.Endpoint {
    private final CompoundTag address;
    private final ServerLevel level;
    private final BlockPos pos;
    private final Object handler;
    private final boolean item;
    CreateWorldCreativeRestore(MinecraftServer server,CreateWorldCreativeTransferPlan.IO io){
        address=io.address();item=address.getString("Kind").equals("item");pos=BlockPos.of(address.getLong("Pos"));
        level=server.getLevel(ResourceKey.create(Registries.DIMENSION,ResourceLocation.parse(address.getString("Dimension"))));
        if(level==null||!level.hasChunkAt(pos))throw new IllegalStateException("Creative блок мира выгружен");
        handler=resolve();verifyAfter();
        if(item){
            var payload=CartRollbackCoordinator.readRecipientItem(address.getCompound("Payload"),server.registryAccess());
            if(payload.isEmpty()||payload.getCount()!=address.getCompound("Payload").getInt("count"))throw new IllegalStateException("Неполный creative предмет мира");
        }else{
            var payload=CartRollbackCoordinator.readAutomationFluid(address.getCompound("Payload"),server.registryAccess());
            if(payload.isEmpty()||payload.getAmount()!=address.getCompound("Payload").getInt("amount"))throw new IllegalStateException("Неполная creative жидкость мира");
        }
        CartRestoreLocks.block(level,pos);CartRestoreLocks.handler(handler);CartRestoreLocks.keepLoaded(level,pos);
    }
    private Object resolve(){
        String sideName=address.getString("Side");
        Direction side=sideName.equals("none")?null:Direction.byName(sideName);
        if(!sideName.equals("none")&&side==null)throw new IllegalStateException("Некорректная сторона creative блока");
        return item?level.getCapability(Capabilities.ItemHandler.BLOCK,pos,side):level.getCapability(Capabilities.FluidHandler.BLOCK,pos,side);
    }
    public void verifyAfter(){
        if(!level.hasChunkAt(pos)||level.getBlockEntity(pos)==null||level.getBlockEntity(pos).isRemoved()
                ||!NbtUtils.writeBlockState(level.getBlockState(pos)).equals(address.getCompound("Block"))
                ||!BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(level.getBlockEntity(pos).getType()).toString().equals(address.getString("BlockEntity"))
                ||resolve()!=handler||handler==null||!handler.getClass().getName().equals(address.getString("Handler")))
            throw new IllegalStateException("Creative блок/обработчик мира заменён");
        if(item){
            var h=(IItemHandler)handler;
            var expected=address.getCompound("Template").isEmpty()?ItemStack.EMPTY:CartRollbackCoordinator.readRecipientItem(address.getCompound("Template"),level.registryAccess());
            if(h.getSlots()!=address.getInt("Size")||h.getSlotLimit(0)!=address.getInt("Limit")
                    ||!ItemStack.matches(expected,h.getStackInSlot(0)))throw new IllegalStateException("Creative шаблон предметов изменён");
        }else{
            var h=(IFluidHandler)handler;
            var expected=address.getCompound("Template").isEmpty()?FluidStack.EMPTY:CartRollbackCoordinator.readAutomationFluid(address.getCompound("Template"),level.registryAccess());
            if(h.getTanks()!=address.getInt("Size")||h.getTankCapacity(0)!=address.getInt("Limit")
                    ||!FluidStack.matches(expected,h.getFluidInTank(0)))throw new IllegalStateException("Creative шаблон жидкости изменён");
        }
    }
    public void apply(){verifyAfter();}
    public void verifyBefore(){verifyAfter();}
    public void compensate(){}
}
