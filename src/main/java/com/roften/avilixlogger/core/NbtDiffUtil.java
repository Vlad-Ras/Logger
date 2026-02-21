package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tiny, safe summary for block-entity NBT changes.
 *
 * We intentionally avoid deep diffs (can be huge). We only report top-level keys that changed,
 * excluding obvious noise (x/y/z/id, Items, etc.).
 */
public final class NbtDiffUtil {

    private NbtDiffUtil() {}

    @Nullable
    public static String summarize(@Nullable String beBeforeSnbt, @Nullable String beAfterSnbt) {
        if (beBeforeSnbt == null || beAfterSnbt == null) return null;
        if (beBeforeSnbt.equals(beAfterSnbt)) return null;
        CompoundTag a;
        CompoundTag b;
        try {
            a = NbtSerde.fromSnbt(beBeforeSnbt);
            b = NbtSerde.fromSnbt(beAfterSnbt);
        } catch (Throwable t) {
            return null;
        }
        if (a == null || b == null) return null;

        Set<String> keys = new HashSet<>();
        keys.addAll(a.getAllKeys());
        keys.addAll(b.getAllKeys());

        // Filter noise.
        keys.remove("x");
        keys.remove("y");
        keys.remove("z");
        keys.remove("id");
        keys.remove("keepPacked");
        keys.remove("CustomName");
        keys.remove("Lock");
        keys.remove("Items");
        keys.remove("inventory");

        List<String> changed = new ArrayList<>();
        for (String k : keys) {
            try {
                boolean hasA = a.contains(k);
                boolean hasB = b.contains(k);
                if (!hasA || !hasB) {
                    changed.add(k + (hasA ? " (removed)" : " (added)"));
                    continue;
                }
                var va = a.get(k);
                var vb = b.get(k);
                if (va == null && vb == null) continue;
                if (va == null || vb == null || !va.equals(vb)) {
                    // Try to add tiny size hints for lists.
                    String hint = "";
                    try {
                        if (va instanceof ListTag la && vb instanceof ListTag lb) {
                            hint = " (" + la.size() + "→" + lb.size() + ")";
                        }
                    } catch (Throwable ignored) {}
                    changed.add(k + hint);
                }
            } catch (Throwable ignored) {}
        }

        if (changed.isEmpty()) return null;
        changed.sort(Comparator.naturalOrder());

        int max = 4;
        StringBuilder sb = new StringBuilder();
        sb.append("NBT: ");
        for (int i = 0; i < changed.size() && i < max; i++) {
            if (i > 0) sb.append(", ");
            sb.append(changed.get(i));
        }
        if (changed.size() > max) sb.append(" …");
        return sb.toString();
    }
}
