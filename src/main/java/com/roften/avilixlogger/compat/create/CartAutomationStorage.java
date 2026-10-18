package com.roften.avilixlogger.compat.create;

import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.CompoundContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.*;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import net.neoforged.neoforge.fluids.*;
import net.neoforged.neoforge.fluids.capability.*;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import java.util.*;

/** Explicit standard storage contracts; unknown and sided adapters fail closed. */
public final class CartAutomationStorage {
    private static final java.lang.reflect.Field[] CHEST_PARTS=chestParts();
    private CartAutomationStorage(){}
    private static java.lang.reflect.Field[] chestParts(){
        try{
            var first=CompoundContainer.class.getDeclaredField("container1");var second=CompoundContainer.class.getDeclaredField("container2");
            first.setAccessible(true);second.setAccessible(true);return new java.lang.reflect.Field[]{first,second};
        }catch(ReflectiveOperationException|RuntimeException unavailable){return null;}
    }
    public static boolean itemSupported(IItemHandler handler){
        if(!(handler instanceof IItemHandlerModifiable))return false;
        if(handler instanceof InvWrapper wrapper&&!(wrapper.getInv() instanceof BlockEntity)
                &&wrapper.getInv().getClass()!=CompoundContainer.class)return false;
        try{
            for(var method:List.of(handler.getClass().getMethod("getSlots"),handler.getClass().getMethod("getStackInSlot",int.class),handler.getClass().getMethod("insertItem",int.class,ItemStack.class,boolean.class),handler.getClass().getMethod("extractItem",int.class,int.class,boolean.class),handler.getClass().getMethod("setStackInSlot",int.class,ItemStack.class))){
                var owner=method.getDeclaringClass();if(owner!=ItemStackHandler.class&&owner!=InvWrapper.class)return false;
            }
            return true;
        }catch(ReflectiveOperationException e){return false;}
    }
    public static boolean fluidSupported(IFluidHandler handler){
        if(!(handler instanceof FluidTank tank)||handler.getTanks()!=1)return false;
        try{
            var setter=handler.getClass().getMethod("setFluid",FluidStack.class).getDeclaringClass();
            return (setter==FluidTank.class||setter==com.simibubi.create.foundation.fluid.SmartFluidTank.class)&&handler.getClass().getMethod("getTanks").getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("getFluidInTank",int.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("fill",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("drain",int.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("drain",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class;
        }catch(ReflectiveOperationException e){return false;}
    }
    public static CompoundTag describe(ServerLevel level,BlockPos pos,String side,String kind,int slot,Object handler){
        if(!level.hasChunkAt(pos))return null;
        CompoundTag partner=null;
        if(kind.equals("item")&&handler instanceof InvWrapper wrapper&&wrapper.getInv() instanceof CompoundContainer combined){
            var pair=chestPair(level,pos,combined);if(pair==null)return null;
            pos=pair[0].getBlockPos();partner=new CompoundTag();
            partner.putLong("Pos",pair[1].getBlockPos().asLong());
            partner.put("Block",NbtUtils.writeBlockState(level.getBlockState(pair[1].getBlockPos())));
            partner.putString("BlockEntity",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(pair[1].getType()).toString());
        }
        var be=level.getBlockEntity(pos);if(be==null||be.isRemoved())return null;
        var n=new CompoundTag();n.putString("Dimension",level.dimension().location().toString());n.putLong("Pos",pos.asLong());n.putString("Side",side);n.putString("Kind",kind);n.putInt("Slot",slot);
        n.put("Block",NbtUtils.writeBlockState(level.getBlockState(pos)));n.putString("BlockEntity",BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString());n.putString("Handler",handler.getClass().getName());
        if(kind.equals("item")&&handler instanceof IItemHandler h&&itemSupported(h)&&slot>=0&&slot<h.getSlots()){
            if(h instanceof InvWrapper wrapper&&wrapper.getInv()!=be&&partner==null)return null;
            if(partner!=null)n.put("DoubleChest",partner);
            n.putInt("Size",h.getSlots());n.putInt("Limit",h.getSlotLimit(slot));return n;
        }
        if(kind.equals("fluid")&&handler instanceof IFluidHandler h&&fluidSupported(h)&&slot==0){n.putInt("Size",h.getTanks());n.putInt("Limit",h.getTankCapacity(slot));return n;}
        return null;
    }
    /** Native chest order is RIGHT then LEFT, regardless of the queried half. No chunk loads. */
    private static ChestBlockEntity[] chestPair(ServerLevel level,BlockPos pos,CompoundContainer combined){
        if(combined.getClass()!=CompoundContainer.class)return null;
        var state=level.getBlockState(pos);
        if(!(state.is(Blocks.CHEST)||state.is(Blocks.TRAPPED_CHEST))||state.getValue(ChestBlock.TYPE)==ChestType.SINGLE)return null;
        var otherPos=pos.relative(ChestBlock.getConnectedDirection(state));
        if(!level.hasChunkAt(otherPos))return null;
        var otherState=level.getBlockState(otherPos);
        if(otherState.getBlock()!=state.getBlock()||otherState.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING)
                ||otherState.getValue(ChestBlock.TYPE)!=(state.getValue(ChestBlock.TYPE)==ChestType.RIGHT?ChestType.LEFT:ChestType.RIGHT))return null;
        var a=level.getBlockEntity(pos);var b=level.getBlockEntity(otherPos);
        Class<?> nativeType=state.is(Blocks.TRAPPED_CHEST)?TrappedChestBlockEntity.class:ChestBlockEntity.class;
        if(!(a instanceof ChestBlockEntity first)||!(b instanceof ChestBlockEntity second)||a.getClass()!=nativeType
                ||b.getClass()!=nativeType||a.isRemoved()||b.isRemoved()||!combined.contains(first)||!combined.contains(second)
                ||first.getContainerSize()!=27||second.getContainerSize()!=27||combined.getContainerSize()!=54)return null;
        var ordered=state.getValue(ChestBlock.TYPE)==ChestType.RIGHT?new ChestBlockEntity[]{first,second}:new ChestBlockEntity[]{second,first};
        // Membership alone cannot distinguish reversed delegation when both halves are empty.
        // If native field access is unavailable, disable this adapter rather than guessing.
        try{if(CHEST_PARTS==null||CHEST_PARTS[0].get(combined)!=ordered[0]||CHEST_PARTS[1].get(combined)!=ordered[1])return null;}
        catch(IllegalAccessException|RuntimeException unavailable){return null;}
        return ordered;
    }
    public static boolean sameAddress(CompoundTag a,CompoundTag b){
        var left=a.copy();var right=b.copy();for(String key:List.of("Item","Fluid","Side")){left.remove(key);right.remove(key);}return left.equals(right);
    }
}
