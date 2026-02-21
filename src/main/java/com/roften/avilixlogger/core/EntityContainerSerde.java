package com.roften.avilixlogger.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/**
 * Converts an Entity-backed {@link Container} inventory into an NBT shape compatible with {@link InventoryDiffUtil}.
 * We store it as a CompoundTag with an "Items" ListTag of item stack tags.
 */
public final class EntityContainerSerde {

    private EntityContainerSerde() {}

    public static String write(Container c, HolderLookup.Provider provider) {
        if (c == null) return null;
        try {
            CompoundTag root = new CompoundTag();
            ListTag items = new ListTag();
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack st = c.getItem(i);
                if (st == null || st.isEmpty()) continue;
                CompoundTag it = new CompoundTag();
                it.putByte("Slot", (byte) i);
                // write full stack (includes Count)
                CompoundTag stackTag = (CompoundTag) st.save(provider);
                // merge
                for (String k : stackTag.getAllKeys()) {
                    it.put(k, stackTag.get(k));
                }
                items.add(it);
            }
            root.put("Items", items);
            root.putInt("Size", c.getContainerSize());
            return NbtSerde.toSnbt(root);
        } catch (Throwable t) {
            return null;
        }
    }

    public static CompoundTag from(String snbt) {
        return NbtSerde.fromSnbt(snbt);
    }
}
