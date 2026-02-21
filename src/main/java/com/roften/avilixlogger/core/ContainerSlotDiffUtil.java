package com.roften.avilixlogger.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Slot-aware diff for {@link ContainerSlotSnapshot} payloads. */
public final class ContainerSlotDiffUtil {
    private ContainerSlotDiffUtil() {}

    public record SlotChange(int slot, ItemStack before, ItemStack after) {}

    /**
     * Strict slot diff: returns list of slot changes.
     */
    public static List<SlotChange> diffSlots(String beforeSnbt, String afterSnbt, HolderLookup.Provider provider) {
        CompoundTag b = NbtSerde.fromSnbt(beforeSnbt);
        CompoundTag a = NbtSerde.fromSnbt(afterSnbt);
        if (b == null || a == null) return List.of();

        int sizeB = b.contains("Size") ? b.getInt("Size") : 0;
        int sizeA = a.contains("Size") ? a.getInt("Size") : 0;
        int size = Math.max(sizeB, sizeA);

        ItemStack[] bs = new ItemStack[size];
        ItemStack[] as = new ItemStack[size];
        for (int i = 0; i < size; i++) { bs[i] = ItemStack.EMPTY; as[i] = ItemStack.EMPTY; }

        fill(bs, b, provider);
        fill(as, a, provider);

        List<SlotChange> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            ItemStack x = bs[i];
            ItemStack y = as[i];
            if (ItemStack.matches(x, y)) continue;
            // matches() ignores count? It checks item+tag, and count? In modern MC matches checks item+tag.
            // We want strict - include count changes too.
            if (ItemStack.isSameItemSameComponents(x, y) && x.getCount() == y.getCount()) continue;
            out.add(new SlotChange(i, x, y));
        }
        return out;
    }

    /**
     * Aggregated delta for human-readable +/-. Keyed by normalized stack (count=1) SNBT.
     */
    public static Map<String, Integer> diffAggregated(String beforeSnbt, String afterSnbt, HolderLookup.Provider provider) {
        CompoundTag b = NbtSerde.fromSnbt(beforeSnbt);
        CompoundTag a = NbtSerde.fromSnbt(afterSnbt);
        if (b == null || a == null) return Map.of();

        Map<String, Integer> out = new HashMap<>();
        Map<String, Integer> bCount = countAll(b, provider);
        Map<String, Integer> aCount = countAll(a, provider);
        for (var ent : aCount.entrySet()) {
            int delta = ent.getValue() - bCount.getOrDefault(ent.getKey(), 0);
            if (delta != 0) out.put(ent.getKey(), delta);
        }
        for (var ent : bCount.entrySet()) {
            if (aCount.containsKey(ent.getKey())) continue;
            int delta = -ent.getValue();
            if (delta != 0) out.put(ent.getKey(), delta);
        }
        return out;
    }

    private static void fill(ItemStack[] arr, CompoundTag tag, HolderLookup.Provider provider) {
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag it = items.getCompound(i);
            int slot = it.contains("Slot") ? (it.getByte("Slot") & 0xFF) : -1;
            if (slot < 0 || slot >= arr.length) continue;
            arr[slot] = ItemStack.parse(provider, it).orElse(ItemStack.EMPTY);
        }
    }

    private static Map<String, Integer> countAll(CompoundTag tag, HolderLookup.Provider provider) {
        Map<String, Integer> m = new HashMap<>();
        ListTag items = tag.getList("Items", Tag.TAG_COMPOUND);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag it = items.getCompound(i);
            ItemStack st = ItemStack.parse(provider, it).orElse(ItemStack.EMPTY);
            if (st.isEmpty()) continue;
            int c = st.getCount();
            ItemStack keySt = st.copy();
            keySt.setCount(1);
            String key = NbtSerde.writeItemStack(keySt, provider);
            m.merge(key, c, Integer::sum);
        }
        return m;
    }
}
