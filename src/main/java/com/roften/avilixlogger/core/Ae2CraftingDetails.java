package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Detached job receipts. A planned output is neither a physical transfer nor proof of completion. */
public final class Ae2CraftingDetails {
    private static final String PREFIX = "ae2-crafting-v1|";
    private Ae2CraftingDetails() {}
    public record Summary(String kind, String resource, String keyType, long amount, String outcome) {
        public String label() { return resource + " · " + amount + (kind.equals("i") ? " шт." : kind.equals("f") ? " мБ" : " ед.") + " · " + status(outcome); }
    }
    public static boolean structured(LogEntry row) {
        return row != null && (row.type == ActionType.ME_CRAFT_REQUEST || row.type == ActionType.ME_CRAFT_CANCEL || row.type == ActionType.ME_CRAFT_FINISH);
    }
    private static boolean valid(String kind, String resource, String keyType, long amount, String outcome, ActionType type) {
        return Set.of("i", "f", "g").contains(kind) && ResourceLocation.tryParse(resource) != null
                && ResourceLocation.tryParse(keyType) != null && amount > 0
                && (type == ActionType.ME_CRAFT_FINISH ? outcome.equals("finished") : type == ActionType.ME_CRAFT_CANCEL ? outcome.equals("cancelled")
                    : type == ActionType.ME_CRAFT_REQUEST && (outcome.equals("accepted") || outcome.equals("rejected")));
    }
    /** evidence is already detached and owned by this row; encoding remains on the event worker. */
    public static void attach(LogEntry row, String kind, String resource, String keyType, long amount, String outcome, CompoundTag evidence) {
        if (!structured(row) || !valid(kind, resource, keyType, amount, outcome, row.type) || evidence == null)
            throw new IllegalArgumentException("Invalid AE2 job receipt");
        evidence.putString("Kind", "Ae2Crafting"); evidence.putInt("Version", 1);
        evidence.putString("ResourceKind", kind); evidence.putString("Resource", resource); evidence.putString("KeyType", keyType);
        evidence.putLong("Amount", amount); evidence.putString("Outcome", outcome); evidence.putString("QuantityMeaning", "planned-output");
        row.extra = PREFIX + kind + '|' + resource + '|' + keyType + '|' + amount + '|' + outcome;
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, evidence);
    }
    public static Summary summary(LogEntry row) {
        if (!structured(row) || row.extra == null || !row.extra.startsWith(PREFIX) || row.extra.length() > 4096) return null;
        try {
            var p = row.extra.substring(PREFIX.length()).split("\\|", -1);
            if (p.length != 5) return null;
            long amount = Long.parseLong(p[3]);
            return valid(p[0], p[1], p[2], amount, p[4], row.type) ? new Summary(p[0], p[1], p[2], amount, p[4]) : null;
        } catch (RuntimeException invalid) { return null; }
    }
    public static CompoundTag context(LogEntry row) {
        var summary = summary(row);
        if (summary == null || row.beAfter == null || row.beAfter.length() > 4_194_304) return null;
        var tag = NbtSerde.fromSnbt(row.beAfter);
        if (tag == null || !tag.getString("Kind").equals("Ae2Crafting") || tag.getInt("Version") != 1
                || !tag.getString("ResourceKind").equals(summary.kind()) || !tag.getString("Resource").equals(summary.resource())
                || !tag.getString("KeyType").equals(summary.keyType()) || !tag.contains("Amount", Tag.TAG_LONG) || tag.getLong("Amount") != summary.amount()
                || !tag.getString("Outcome").equals(summary.outcome()) || !tag.getString("QuantityMeaning").equals("planned-output")) return null;
        return tag;
    }
    public static String status(String outcome) { return switch (outcome) { case "accepted" -> "принят"; case "rejected" -> "отказ"; case "cancelled" -> "отменён"; case "finished" -> "завершён процессором"; default -> outcome; }; }
    public static List<Component> describe(LogEntry row) {
        var s = summary(row); if (s == null) return List.of();
        var out = new ArrayList<Component>(); out.add(Component.literal("Заказ МЭ: " + s.label()));
        out.add(Component.literal("Количество относится к планируемому результату задания"));
        var tag = context(row);
        if (tag != null) {
            if (tag.hasUUID("JobId")) out.add(Component.literal("ID задания: " + tag.getUUID("JobId")));
            if (tag.contains("ErrorCode", Tag.TAG_STRING)) out.add(Component.literal("Причина отказа AE2: " + tag.getString("ErrorCode")));
            if (tag.getBoolean("RequestLinkMissing")) out.add(Component.literal("Связь с заказчиком отсутствовала; запись привязана к процессору"));
            if (tag.getString("CancellationOrigin").equals("cpu-link")) out.add(Component.literal("Отмена через ссылку задания процессора"));
            if (tag.getBoolean("NativeThrew")) out.add(Component.literal(switch (s.outcome()) {
                case "finished" -> "Задание завершено, затем операция завершилась ошибкой";
                case "cancelled" -> "Отмена подтверждена, затем операция завершилась ошибкой";
                default -> "Задание создано, затем операция завершилась ошибкой";
            }));
            if (tag.contains("Cpu", Tag.TAG_COMPOUND)) {
                var cpu = tag.getCompound("Cpu");
                out.add(Component.literal("Процессор: " + cpu.getString("Dimension") + " @ " + cpu.getString("BoundsMin") + " → " + cpu.getString("BoundsMax")));
                if (!cpu.getString("Name").isEmpty()) out.add(Component.literal("Имя процессора: " + cpu.getString("Name")));
            }
        }
        out.add(Component.literal("Аудит задания; производство предметов и возврат ресурсов проверяются отдельно"));
        return List.copyOf(out);
    }
}
