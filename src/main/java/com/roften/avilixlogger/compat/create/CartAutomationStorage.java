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
            for(var method:List.of(handler.getClass().getMethod("getSlots"),handler.getClass().getMethod("getStackInSlot",int.class),handler.getClass().getMethod("insertItem",int.class,ItemStack.class,boolean.class),handler.getClass().getMethod("extractItem",int.class,int.class,boolean.class),handler.getClass().getMethod("setStackInSlot",int.class,ItemStack.class),handler.getClass().getMethod("getSlotLimit",int.class),handler.getClass().getMethod("isItemValid",int.class,ItemStack.class))){
                var owner=method.getDeclaringClass();if(owner!=ItemStackHandler.class&&owner!=InvWrapper.class)return false;
            }
            return true;
        }catch(ReflectiveOperationException e){return false;}
    }
    public static boolean fluidSupported(IFluidHandler handler){
        if(!(handler instanceof FluidTank tank)||handler.getTanks()!=1)return false;
        try{
            var setter=handler.getClass().getMethod("setFluid",FluidStack.class).getDeclaringClass();
            return (setter==FluidTank.class||setter==com.simibubi.create.foundation.fluid.SmartFluidTank.class)&&handler.getClass().getMethod("getTanks").getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("getFluidInTank",int.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("getTankCapacity",int.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("isFluidValid",int.class,FluidStack.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("isFluidValid",FluidStack.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("fill",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("drain",int.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class&&handler.getClass().getMethod("drain",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class;
        }catch(ReflectiveOperationException e){return false;}
    }
    public static CompoundTag describe(ServerLevel level,BlockPos pos,String side,String kind,int slot,Object handler){
        if(!level.hasChunkAt(pos))return null;
        if(kind.equals("item") && handler.getClass()==net.neoforged.neoforge.items.wrapper.SidedInvWrapper.class
                && handler instanceof com.roften.avilixlogger.compat.create.mixin.SidedContainerAccessor access){
            var container=access.avilixlogger$container();
            if(container!=level.getBlockEntity(pos))return null;
            int physical=net.neoforged.neoforge.items.wrapper.SidedInvWrapper.getSlot(container,slot,access.avilixlogger$side());
            return physical<0?null:describeContainer(level,pos,side,physical,container);
        }
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
        if(kind.equals("item")&&handler.getClass()==net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class
                &&be.getClass()==com.simibubi.create.content.processing.basin.BasinBlockEntity.class
                &&handler instanceof com.roften.avilixlogger.compat.create.mixin.CombinedInventoryAccessor access){
            var basin=(com.simibubi.create.content.processing.basin.BasinBlockEntity)be;
            var children=access.avilixlogger$children();
            if(children.length!=2||children[0]!=basin.getInputInventory()||children[1]!=basin.getOutputInventory()
                    ||children[0].getSlots()!=9||children[1].getSlots()!=9||slot<0||slot>=18
                    ||((IItemHandler)handler).getSlots()!=18)return null;
            n.putString("Composite","create:basin_input_output");n.putInt("FirstSize",9);
            n.putInt("Size",18);n.putInt("Limit",((IItemHandler)handler).getSlotLimit(slot));return n;
        }
        if(kind.equals("fluid")&&handler.getClass()==com.simibubi.create.foundation.fluid.CombinedTankWrapper.class
                &&be.getClass()==com.simibubi.create.content.processing.basin.BasinBlockEntity.class
                &&handler instanceof com.roften.avilixlogger.compat.create.mixin.CombinedTankAccessor access){
            var basin=(com.simibubi.create.content.processing.basin.BasinBlockEntity)be;
            var output=((com.roften.avilixlogger.compat.create.mixin.BasinOutputTankAccessor)basin).avilixlogger$outputTank();
            var children=access.avilixlogger$children();
            if(children.length!=2||children[0]!=output.getCapability()||children[1]!=basin.inputTank.getCapability()
                    ||children[0].getTanks()!=2||children[1].getTanks()!=2||slot<0||slot>=4
                    ||((IFluidHandler)handler).getTanks()!=4)return null;
            n.putString("Composite","create:basin_output_input");n.putInt("FirstSize",2);
            n.putInt("Size",4);n.putInt("Limit",((IFluidHandler)handler).getTankCapacity(slot));return n;
        }
        if(kind.equals("item")&&handler instanceof IItemHandler h&&itemSupported(h)&&slot>=0&&slot<h.getSlots()){
            if(h instanceof InvWrapper wrapper&&wrapper.getInv()!=be&&partner==null)return null;
            if(partner!=null)n.put("DoubleChest",partner);
            n.putInt("Size",h.getSlots());n.putInt("Limit",h.getSlotLimit(slot));return n;
        }
        if(kind.equals("fluid")&&handler instanceof IFluidHandler h&&fluidSupported(h)&&slot==0){n.putInt("Size",h.getTanks());n.putInt("Limit",h.getTankCapacity(slot));return n;}
        return null;
    }
    /** Direct vanilla Container storage; never substitute a capability adapter with different IO semantics. */
    public static net.minecraft.world.Container containerAt(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos))return null;var be=level.getBlockEntity(pos);if(be==null||be.isRemoved())return null;
        if(!Set.of(ChestBlockEntity.class,TrappedChestBlockEntity.class,net.minecraft.world.level.block.entity.HopperBlockEntity.class,
                net.minecraft.world.level.block.entity.BarrelBlockEntity.class,net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity.class,
                net.minecraft.world.level.block.entity.DispenserBlockEntity.class,net.minecraft.world.level.block.entity.DropperBlockEntity.class,
                net.minecraft.world.level.block.entity.FurnaceBlockEntity.class,net.minecraft.world.level.block.entity.BlastFurnaceBlockEntity.class,
                net.minecraft.world.level.block.entity.SmokerBlockEntity.class,net.minecraft.world.level.block.entity.BrewingStandBlockEntity.class).contains(be.getClass()))return null;
        var state=level.getBlockState(pos);
        if(state.getBlock() instanceof ChestBlock chest){
            if(state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE&&!level.hasChunkAt(pos.relative(ChestBlock.getConnectedDirection(state))))return null;
            return ChestBlock.getContainer(chest,state,level,pos,true);
        }
        return be instanceof net.minecraft.world.Container container?container:null;
    }
    public static CompoundTag describeContainer(ServerLevel level,BlockPos pos,String side,int slot,net.minecraft.world.Container container){
        var canonical=containerAt(level,pos);if(canonical==null)return null;
        if(!(container instanceof CompoundContainer)&&canonical!=container)return null;
        var result=describe(level,pos,side,"item",slot,new InvWrapper(container));
        if(result!=null)result.putBoolean("DirectContainer",true);return result;
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
