package com.roften.avilixlogger.core;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

import java.util.*;

/**
 * Utility to compute what items were put into / taken from a container by comparing
 * two BlockEntity NBT snapshots.
 *
 * This is intentionally best-effort to keep compatibility with modded storages.
 */
public final class InventoryDiffUtil {

    private InventoryDiffUtil() {}

    public record Delta(String normalizedStackSnbt, ItemStack representative, int deltaCount) {}

    /**
     * Returns a list of deltas (positive = put, negative = take).
     */
    public static List<Delta> diff(String beforeBeSnbt, String afterBeSnbt, HolderLookup.Provider provider) {
        try {
            CompoundTag before = NbtSerde.fromSnbt(beforeBeSnbt);
            CompoundTag after = NbtSerde.fromSnbt(afterBeSnbt);
            if (before == null || after == null) return List.of();

            Map<String, StackAgg> a = aggregateStacks(before, provider);
            Map<String, StackAgg> b = aggregateStacks(after, provider);

            if (a.isEmpty() && b.isEmpty()) return List.of();

            Set<String> keys = new HashSet<>();
            keys.addAll(a.keySet());
            keys.addAll(b.keySet());

            List<Delta> out = new ArrayList<>();
            for (String k : keys) {
                int ca = a.getOrDefault(k, StackAgg.EMPTY).count;
                int cb = b.getOrDefault(k, StackAgg.EMPTY).count;
                int d = cb - ca;
                if (d != 0) {
                    ItemStack rep = (d > 0 ? b.get(k) : a.get(k)).representative;
                    if (rep == null) rep = ItemStack.EMPTY;
                    out.add(new Delta(k, rep, d));
                }
            }

            // Order by magnitude, then item id for stable output
            out.sort((x, y) -> {
                int c = Integer.compare(Math.abs(y.deltaCount), Math.abs(x.deltaCount));
                if (c != 0) return c;
                return x.normalizedStackSnbt.compareTo(y.normalizedStackSnbt);
            });
            return out;
        } catch (Throwable t) {
            return List.of();
        }
    }

    private static Map<String, StackAgg> aggregateStacks(CompoundTag beTag, HolderLookup.Provider provider) {
        // Many modded containers nest inventory lists a few levels deep.
        // Try to find an item list best-effort, with a small depth limit to keep it cheap.
        ListTag items = findItemListRecursive(beTag, 3);

        if (items == null || items.isEmpty()) return Map.of();

        Map<String, StackAgg> map = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            Tag t = items.get(i);
            if (!(t instanceof CompoundTag it)) continue;

            CompoundTag stackTag = it.copy();
            stackTag.remove("Slot"); // normalize (vanilla)
            stackTag.remove("slot"); // normalize (some mods)

            ItemStack st = NbtSerde.readItemStack(NbtSerde.toSnbt(stackTag), provider);
            if (st == null || st.isEmpty()) continue;

            int count = Math.max(1, st.getCount());

            // Create normalized key so that different stack splits aggregate together.
            ItemStack norm = st.copy();
            norm.setCount(1);
            String key = NbtSerde.writeItemStack(norm, provider);
            if (key == null) continue;

            StackAgg agg = map.get(key);
            if (agg == null) {
                map.put(key, new StackAgg(count, st.copy()));
            } else {
                agg.count += count;
            }
        }
        return map;
    }

    private static final String[] COMMON_ITEM_LIST_KEYS = new String[]{
            "Items", "items", "Inventory", "inventory", "Inv", "inv",
            "contents", "Contents", "content", "Content",
            "handler", "Handler", "item_handler", "ItemHandler",
            "storage", "Storage"
    };

    /**
     * Finds the first ListTag<CompoundTag> that looks like an inventory list.
     * Depth is intentionally small because this can run on the hot path (container close).
     */
    private static ListTag findItemListRecursive(CompoundTag root, int maxDepth) {
        if (root == null) return null;

        record Node(CompoundTag tag, int depth) {}
        ArrayDeque<Node> q = new ArrayDeque<>();
        q.add(new Node(root, 0));

        while (!q.isEmpty()) {
            Node n = q.removeFirst();
            CompoundTag tag = n.tag();
            int depth = n.depth();

            // 1) Common keys first.
            for (String k : COMMON_ITEM_LIST_KEYS) {
                if (!tag.contains(k, Tag.TAG_LIST)) continue;
                ListTag l = tag.getList(k, Tag.TAG_COMPOUND);
                if (looksLikeItemList(l)) return l;
            }

            // 2) Heuristic: any list that looks like ItemStack entries.
            for (String k : tag.getAllKeys()) {
                if (!tag.contains(k, Tag.TAG_LIST)) continue;
                ListTag l = tag.getList(k, Tag.TAG_COMPOUND);
                if (looksLikeItemList(l)) return l;
            }

            // 3) Recurse into child compounds (depth-limited).
            if (depth >= maxDepth) continue;
            for (String k : tag.getAllKeys()) {
                if (!tag.contains(k, Tag.TAG_COMPOUND)) continue;
                CompoundTag child = tag.getCompound(k);
                if (child == null || child.isEmpty()) continue;
                q.addLast(new Node(child, depth + 1));
            }
        }
        return null;
    }

    private static boolean looksLikeItemList(ListTag l) {
        if (l == null || l.isEmpty()) return false;
        // Don't rely on the first element: some mods store a dummy/empty entry at index 0.
        // Scan a few entries and accept the list if ANY looks like an ItemStack tag.
        int scan = Math.min(8, l.size());
        for (int i = 0; i < scan; i++) {
            Tag t = l.get(i);
            if (!(t instanceof CompoundTag c)) continue;
            // Typical stack tags contain "id" and "Count" (vanilla), or sometimes "count" (mods).
            if (c.contains("id", Tag.TAG_STRING)
                    || c.contains("Count", Tag.TAG_BYTE)
                    || c.contains("count", Tag.TAG_INT)
                    || c.contains("Count", Tag.TAG_INT)) {
                return true;
            }
        }
        return false;
    }

    private static final class StackAgg {
        static final StackAgg EMPTY = new StackAgg(0, ItemStack.EMPTY);
        int count;
        final ItemStack representative;
        StackAgg(int count, ItemStack representative) {
            this.count = count;
            this.representative = representative;
        }
    }
}
