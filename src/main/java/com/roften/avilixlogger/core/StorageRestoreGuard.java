package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.IItemHandler;
import java.util.Set;

/** Handler reservation contract shared with Create rollback, without loading optional Create classes. */
public final class StorageRestoreGuard {
    private static final Set<String> ITEM_OWNERS=Set.of(
            "net.neoforged.neoforge.items.ItemStackHandler",
            "net.neoforged.neoforge.items.wrapper.InvWrapper",
            "net.neoforged.neoforge.items.wrapper.CombinedInvWrapper",
            "com.simibubi.create.content.logistics.depot.DepotItemHandler");
    private StorageRestoreGuard() {}
    public static void reserve(ServerLevel level, BlockPos pos) {
        reserveSide(level,pos,null);
        for (Direction side:Direction.values()) reserveSide(level,pos,side);
        if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) CartRestoreLocks.container(container);
    }
    private static void reserveSide(ServerLevel level,BlockPos pos,Direction side) {
        var item=level.getCapability(Capabilities.ItemHandler.BLOCK,pos,side);
        if (item!=null && !supportedItem(item)) throw new IllegalStateException("Инвентарь не поддерживает безопасную блокировку: "+pos+", сторона "+side+", "+item.getClass().getName());
        CartRestoreLocks.handler(item);
        var fluid=level.getCapability(Capabilities.FluidHandler.BLOCK,pos,side);
        if (fluid!=null && !supportedFluid(fluid)) throw new IllegalStateException("Жидкостное хранилище не поддерживает безопасную блокировку: "+pos+", сторона "+side+", "+fluid.getClass().getName());
        CartRestoreLocks.handler(fluid);
    }
    private static boolean supportedItem(IItemHandler handler) {
        try {
            return ITEM_OWNERS.contains(handler.getClass().getMethod("insertItem",int.class,ItemStack.class,boolean.class).getDeclaringClass().getName())
                    && ITEM_OWNERS.contains(handler.getClass().getMethod("extractItem",int.class,int.class,boolean.class).getDeclaringClass().getName());
        } catch (ReflectiveOperationException failure) {return false;}
    }
    private static boolean supportedFluid(IFluidHandler handler) {
        try {
            return handler.getClass().getMethod("fill",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class
                    && handler.getClass().getMethod("drain",int.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class
                    && handler.getClass().getMethod("drain",FluidStack.class,IFluidHandler.FluidAction.class).getDeclaringClass()==FluidTank.class;
        } catch (ReflectiveOperationException failure) {return false;}
    }
}
