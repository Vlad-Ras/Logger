package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Detached ME evidence; reading history never loads AE2 classes or reconstructs a mod key. */
public final class Ae2TransferDetails {
    private static final Set<String> KINDS = Set.of("i", "f", "g");
    private static final String PREFIX = "ae2-transfer-v1|";
    private Ae2TransferDetails() {}
    public record Summary(String kind, String resource, String keyType, long amount) {
        public String amountLabel() { return kind.equals("i") ? "x" + amount : amount + (kind.equals("f") ? " мБ" : " ед."); }
        public String label() { return resource + " · " + amountLabel(); }
    }
    public static boolean transfer(LogEntry row) { return row != null && (row.type == ActionType.ME_PUT || row.type == ActionType.ME_TAKE); }

    /** Inputs and snapshots are detached; SNBT encoding stays with LogEntry's event worker. */
    public static void attach(LogEntry row, String kind, String resource, String keyType, long amount,
                              String unit, int scale, CompoundTag key) {
        if (!transfer(row) || !KINDS.contains(kind) || amount <= 0 || scale <= 0
                || ResourceLocation.tryParse(resource) == null || ResourceLocation.tryParse(keyType) == null)
            throw new IllegalArgumentException("Invalid ME transfer evidence");
        var evidence = new CompoundTag();
        evidence.putString("Kind", "Ae2Transfer"); evidence.putInt("Version", 1);
        evidence.putString("Resource", resource); evidence.putString("KeyType", keyType);
        evidence.putLong("Amount", amount); evidence.putString("ResourceKind", kind);
        evidence.putString("NativeUnit", Objects.requireNonNullElse(unit, "")); evidence.putInt("AmountPerUnit", scale);
        if (key != null) evidence.put("Key", key);
        row.extra = PREFIX + kind + '|' + resource + '|' + keyType + '|' + amount;
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, evidence);
    }

    public static Summary summary(LogEntry row) {
        if (!transfer(row) || row.extra == null || !row.extra.startsWith(PREFIX) || row.extra.length() > 4096) return null;
        try {
            var parts = row.extra.substring(PREFIX.length()).split("\\|", -1);
            if (parts.length != 4 || !KINDS.contains(parts[0])
                    || ResourceLocation.tryParse(parts[1]) == null || ResourceLocation.tryParse(parts[2]) == null) return null;
            long amount = Long.parseLong(parts[3]);
            return amount > 0 ? new Summary(parts[0], parts[1], parts[2], amount) : null;
        } catch (RuntimeException invalid) { return null; }
    }

    public static CompoundTag context(LogEntry row) {
        var summary = summary(row);
        if (summary == null || row.beAfter == null || row.beAfter.length() > 4_194_304) return null;
        var tag = NbtSerde.fromSnbt(row.beAfter);
        if (tag == null || !tag.getString("Kind").equals("Ae2Transfer") || tag.getInt("Version") != 1
                || !tag.contains("Amount", Tag.TAG_LONG) || tag.getLong("Amount") != summary.amount()
                || !tag.getString("Resource").equals(summary.resource()) || !tag.getString("KeyType").equals(summary.keyType())
                || !tag.getString("ResourceKind").equals(summary.kind()) || tag.getInt("AmountPerUnit") <= 0) return null;
        return tag;
    }

    public static List<Component> describe(LogEntry row) {
        var summary = summary(row); if (summary == null) return List.of();
        var out = new ArrayList<Component>();
        out.add(Component.literal("МЭ-ресурс: " + summary.resource()));
        out.add(Component.literal("Фактически перенесено: " + summary.amountLabel()));
        out.add(Component.literal("Тип ключа: " + summary.keyType()));
        var tag = context(row);
        if (tag != null) {
            out.add(Component.literal("Единица AE2: " + tag.getInt("AmountPerUnit") + " → " + tag.getString("NativeUnit")));
            if (tag.contains("Key", Tag.TAG_COMPOUND)) out.add(Component.literal("Полный ключ ресурса сохранён в снимке"));
        }
        return List.copyOf(out);
    }
}
