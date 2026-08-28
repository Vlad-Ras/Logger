package com.roften.avilixlogger.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Snapshots non-player slots of any vanilla or modded menu without knowing its implementation. */
public final class GenericMenuSnapshot {
    private GenericMenuSnapshot() {}

    public static String write(AbstractContainerMenu menu, HolderLookup.Provider provider) {
        if (menu == null || provider == null || menu.slots == null || menu.slots.isEmpty()) return null;
        try {
            CompoundTag root = new CompoundTag();
            root.putInt("Size", menu.slots.size());
            ListTag items = new ListTag();
            int externalSlots = 0;
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot == null || slot.container instanceof Inventory) continue;
                externalSlots++;
                ItemStack stack = slot.getItem();
                if (stack == null || stack.isEmpty()) continue;
                CompoundTag item = new CompoundTag();
                item.putInt("Slot", i);
                Tag saved = stack.save(provider);
                if (saved instanceof CompoundTag compound) item.merge(compound);
                items.add(item);
            }
            if (externalSlots == 0) return null;
            root.putInt("ExternalSlots", externalSlots);
            root.put("Items", items);
            return NbtSerde.toSnbt(root);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
