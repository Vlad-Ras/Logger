package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

public final class CartPickupCapture {
    public static final ThreadLocal<CartPickupCapture> CURRENT = new ThreadLocal<>();
    public CompoundTag before;
    public ItemStack packed;
}
