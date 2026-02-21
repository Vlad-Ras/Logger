package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Universal container snapshot/restore helpers.
 *
 * Goal: work on vanilla + modded storages (Create, etc.) by prioritizing IItemHandler capability.
 *
 * We intentionally encode snapshots as a vanilla-like CompoundTag with an "Items" ListTag,
 * so InventoryDiffUtil can reuse its heuristics and Rollback can restore deterministically.
 */
public final class InventorySnapshotUtil {

    private InventorySnapshotUtil() {}

    /**
     * Snapshot container inventory at a position.
     *
     * Priority:
     *  1) IItemHandler capability (all sides, then null)
     *  2) BlockEntity implements Container
     */
    public static String snapshotBlockEntity(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return null;
        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return null;

        // 1) Capability-based (modded)
        try {
            Object handler = tryGetItemHandler(be, null);
            if (handler == null) {
                for (Direction d : Direction.values()) {
                    handler = tryGetItemHandler(be, d);
                    if (handler != null) break;
                }
            }
            if (handler != null) {
                CompoundTag snap = snapshotItemHandler(handler, level.registryAccess());
                return NbtSerde.toSnbt(snap);
            }
        } catch (Throwable ignored) {}

        // 2) Vanilla container interface
        try {
            if (be instanceof Container c) {
                CompoundTag snap = snapshotContainer(c, level.registryAccess());
                return NbtSerde.toSnbt(snap);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    /**
     * Snapshot the "top" inventory slots of a menu (best-effort fallback).
     */
    public static String snapshotMenuTop(AbstractContainerMenu menu, Inventory playerInv, HolderLookup.Provider provider) {
        if (menu == null || provider == null) return null;
        try {
            CompoundTag root = new CompoundTag();
            ListTag items = new ListTag();

            int slotIndex = 0;
            for (Slot s : menu.slots) {
                // Filter player inventory slots out when possible.
                try {
                    if (playerInv != null) {
                        Object cont = null;
                        try {
                            Field f = Slot.class.getDeclaredField("container");
                            f.setAccessible(true);
                            cont = f.get(s);
                        } catch (Throwable ignored) {
                        }
                        if (cont == playerInv) continue;
                    }
                } catch (Throwable ignored) {}

                ItemStack st = s.getItem();
                if (st == null || st.isEmpty()) { slotIndex++; continue; }
                CompoundTag it = NbtSerde.fromSnbt(NbtSerde.writeItemStack(st, provider));
                if (it == null) { slotIndex++; continue; }
                it.putInt("Slot", slotIndex);
                items.add(it);
                slotIndex++;
            }

            // IMPORTANT: Even if the container becomes empty, we must return a valid snapshot.
            // Otherwise we lose the ability to detect removals (before != after, but after == null).
            root.put("Items", items);
            return NbtSerde.toSnbt(root);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Apply a previously captured snapshot to a block entity at pos.
     *
     * Best-effort:
     *  1) IItemHandlerModifiable capability
     *  2) BlockEntity implements Container
     */
    public static boolean applyToBlockEntity(ServerLevel level, BlockPos pos, String snapshotSnbt) {
        if (level == null || pos == null || snapshotSnbt == null || snapshotSnbt.isBlank()) return false;
        CompoundTag root = NbtSerde.fromSnbt(snapshotSnbt);
        if (root == null) return false;
        ListTag items = root.getList("Items", Tag.TAG_COMPOUND);
        if (items == null) return false;

        BlockEntity be = level.getBlockEntity(pos);
        if (be == null) return false;

        // 1) Modded: IItemHandlerModifiable
        try {
            Object handler = tryGetItemHandler(be, null);
            if (handler == null) {
                for (Direction d : Direction.values()) {
                    handler = tryGetItemHandler(be, d);
                    if (handler != null) break;
                }
            }
            if (handler != null && isModifiableItemHandler(handler)) {
                boolean ok = applyToItemHandlerModifiable(handler, items, level.registryAccess());
                if (ok) {
                    be.setChanged();
                    // minimal client update
                    level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 2);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        // 2) Vanilla: Container
        try {
            if (be instanceof Container c) {
                boolean ok = applyToContainer(c, items, level.registryAccess());
                if (ok) {
                    be.setChanged();
                    level.sendBlockUpdated(pos, level.getBlockState(pos), level.getBlockState(pos), 2);
                    return true;
                }
            }
        } catch (Throwable ignored) {}

        return false;
    }

    // -------------------- snapshot impl --------------------

    private static CompoundTag snapshotContainer(Container c, HolderLookup.Provider provider) {
        CompoundTag root = new CompoundTag();
        ListTag items = new ListTag();
        int size = c.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack st = c.getItem(i);
            if (st == null || st.isEmpty()) continue;
            CompoundTag it = NbtSerde.fromSnbt(NbtSerde.writeItemStack(st, provider));
            if (it == null) continue;
            it.putInt("Slot", i);
            items.add(it);
        }
        root.put("Items", items);
        return root;
    }

    private static CompoundTag snapshotItemHandler(Object handler, HolderLookup.Provider provider) throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag items = new ListTag();

        Method getSlots = handler.getClass().getMethod("getSlots");
        Method getStackInSlot = handler.getClass().getMethod("getStackInSlot", int.class);
        int slots = ((Number) getSlots.invoke(handler)).intValue();
        for (int i = 0; i < slots; i++) {
            Object o = getStackInSlot.invoke(handler, i);
            if (!(o instanceof ItemStack st) || st.isEmpty()) continue;
            CompoundTag it = NbtSerde.fromSnbt(NbtSerde.writeItemStack(st, provider));
            if (it == null) continue;
            it.putInt("Slot", i);
            items.add(it);
        }
        root.put("Items", items);
        return root;
    }

    // -------------------- apply impl --------------------

    private static boolean applyToContainer(Container c, ListTag items, HolderLookup.Provider provider) {
        try {
            // Clear first
            int size = c.getContainerSize();
            for (int i = 0; i < size; i++) c.setItem(i, ItemStack.EMPTY);

            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof CompoundTag it)) continue;
                int slot = it.contains("Slot") ? it.getInt("Slot") : i;
                if (slot < 0 || slot >= size) continue;
                ItemStack st = NbtSerde.readItemStack(NbtSerde.toSnbt(it), provider);
                if (st == null) st = ItemStack.EMPTY;
                c.setItem(slot, st);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean applyToItemHandlerModifiable(Object handler, ListTag items, HolderLookup.Provider provider) {
        try {
            Method getSlots = handler.getClass().getMethod("getSlots");
            int slots = ((Number) getSlots.invoke(handler)).intValue();

            Method setStackInSlot = handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);

            // Clear first
            for (int i = 0; i < slots; i++) {
                setStackInSlot.invoke(handler, i, ItemStack.EMPTY);
            }

            for (int i = 0; i < items.size(); i++) {
                if (!(items.get(i) instanceof CompoundTag it)) continue;
                int slot = it.contains("Slot") ? it.getInt("Slot") : i;
                if (slot < 0 || slot >= slots) continue;
                ItemStack st = NbtSerde.readItemStack(NbtSerde.toSnbt(it), provider);
                if (st == null) st = ItemStack.EMPTY;
                setStackInSlot.invoke(handler, slot, st);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // -------------------- capability reflection --------------------

    private static Object tryGetItemHandler(BlockEntity be, Direction side) {
        try {
            Object cap = resolveItemHandlerBlockCapability();
            if (cap == null) return null;

            // Find getCapability method compatible with (cap, context)
            for (Method m : be.getClass().getMethods()) {
                if (!m.getName().equals("getCapability")) continue;
                Class<?>[] p = m.getParameterTypes();
                if (p.length != 2) continue;
                if (!p[0].isInstance(cap)) continue;
                // 2nd arg typically is Direction
                Object r = m.invoke(be, cap, side);
                if (r != null) return r;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Object resolveItemHandlerBlockCapability() {
        // NeoForge 21.1+: Capabilities.ItemHandler.BLOCK
        try {
            Class<?> caps = Class.forName("net.neoforged.neoforge.capabilities.Capabilities");

            // 1) Try nested class ItemHandler with static field BLOCK
            for (Class<?> inner : caps.getClasses()) {
                if (!inner.getSimpleName().equals("ItemHandler")) continue;
                Field f = inner.getField("BLOCK");
                return f.get(null);
            }

            // 2) Some mappings expose ItemHandler as a static field holding a nested holder.
            try {
                Field ih = caps.getField("ItemHandler");
                Object holder = ih.get(null);
                if (holder != null) {
                    try {
                        Field f = holder.getClass().getField("BLOCK");
                        return f.get(holder);
                    } catch (Throwable ignored) {}
                }
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isModifiableItemHandler(Object handler) {
        if (handler == null) return false;
        // Check by method presence (avoid hard linking).
        try {
            handler.getClass().getMethod("setStackInSlot", int.class, ItemStack.class);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
