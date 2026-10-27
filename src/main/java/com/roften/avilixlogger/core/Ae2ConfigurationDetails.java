package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Detached configuration evidence; history never instantiates an AE2 item, menu or key. */
public final class Ae2ConfigurationDetails {
    private static final Set<String> KINDS = Set.of("empty", "i", "f", "g");
    private static final String PREFIX = "ae2-config-v1|";
    private Ae2ConfigurationDetails() {}
    public record Resource(String kind, String id, long amount) {
        public Resource {
            if (!KINDS.contains(kind)
                    || (kind.equals("empty") ? !id.isEmpty() || amount != 0 : ResourceLocation.tryParse(id) == null))
                throw new IllegalArgumentException("Invalid ME configuration resource");
        }
        public String label() {
            return kind.equals("empty") ? "пусто" : id + (kind.equals("i") ? " x" : " ") + amount
                    + (kind.equals("f") ? " мБ" : kind.equals("g") ? " ед." : "");
        }
    }
    public record Pair(CompoundTag before, CompoundTag after) {}
    public static boolean configuration(LogEntry row) {
        return row != null && row.type != null && switch (row.type) {
            case ME_FILTER_CHANGE, ME_UPGRADE_CHANGE, ME_CELL_CHANGE, ME_SETTING_CHANGE -> true;
            default -> false;
        };
    }
    public static ActionType typeForSemantic(String semantic) {
        if (semantic == null) return null;
        return switch (semantic) {
            case "CONFIG", "PROCESSING_INPUTS", "PROCESSING_OUTPUTS" -> ActionType.ME_FILTER_CHANGE;
            case "UPGRADE" -> ActionType.ME_UPGRADE_CHANGE;
            case "VIEW_CELL", "STORAGE_CELL" -> ActionType.ME_CELL_CHANGE;
            default -> null;
        };
    }
    public static boolean structured(LogEntry row) { return configuration(row) && row.extra != null && row.extra.startsWith(PREFIX); }
    public static String label(LogEntry row) { return structured(row) ? row.extra.substring(PREFIX.length()) : null; }
    /** Both full item tags transfer ownership to the row; native serialization happens before this call. */
    public static void slot(LogEntry row, String semantic, int slot, Resource before, Resource after,
                            CompoundTag beforeItem, CompoundTag afterItem) {
        if (!configuration(row) || row.type != typeForSemantic(semantic) || slot < 0)
            throw new IllegalArgumentException("Invalid ME slot configuration");
        var old = state(before, beforeItem); var now = state(after, afterItem);
        for (var tag : List.of(old, now)) { header(tag, "slot"); tag.putInt("Slot", slot); tag.putString("Semantic", semantic); }
        row.extra = PREFIX + semantic + " #" + slot + ": " + before.label() + " → " + after.label()
                + (before.equals(after) ? " · изменено состояние предмета" : "");
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, old);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, now);
        CompoundTag representative = afterItem == null ? beforeItem : afterItem;
        if (representative != null) row.deferSnapshot(LogEntry.SnapshotField.ITEM, representative);
    }
    public static void setting(LogEntry row, String name, String before, String after) {
        if (row.type != ActionType.ME_SETTING_CHANGE || name == null || name.isBlank())
            throw new IllegalArgumentException("Invalid ME setting");
        var old = settingState(name, before); var now = settingState(name, after);
        row.extra = PREFIX + name + ": " + Objects.requireNonNullElse(before, "отсутствует") + " → " + Objects.requireNonNullElse(after, "отсутствует");
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, old);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, now);
    }
    private static CompoundTag settingState(String name, String value) {
        var tag = new CompoundTag(); header(tag, "setting"); tag.putString("Setting", name);
        tag.putBoolean("Present", value != null); if (value != null) tag.putString("Value", value); return tag;
    }
    private static void header(CompoundTag tag, String domain) {
        tag.putString("Kind", "Ae2Configuration"); tag.putInt("Version", 1); tag.putString("Domain", domain);
    }
    private static CompoundTag state(Resource resource, CompoundTag item) {
        if (!resource.kind().equals("empty") && (item == null || !item.contains("id", Tag.TAG_STRING)))
            throw new IllegalArgumentException("Missing complete ME configuration item");
        if (resource.kind().equals("empty") && item != null) throw new IllegalArgumentException("Empty ME slot has an item payload");
        var tag = new CompoundTag(); tag.putString("ResourceKind", resource.kind()); tag.putString("Resource", resource.id());
        tag.putLong("Amount", resource.amount()); if (item != null) tag.put("Item", item); return tag;
    }
    public static Pair context(LogEntry row) {
        if (!structured(row) || row.beBefore == null || row.beAfter == null
                || row.beBefore.length() > 4_194_304 || row.beAfter.length() > 4_194_304) return null;
        var before = NbtSerde.fromSnbt(row.beBefore); var after = NbtSerde.fromSnbt(row.beAfter);
        if (!valid(before) || !valid(after) || !before.getString("Domain").equals(after.getString("Domain"))) return null;
        String domain = before.getString("Domain");
        if (domain.equals("slot") && (row.type != typeForSemantic(before.getString("Semantic")) || before.getInt("Slot") != after.getInt("Slot")
                || !before.getString("Semantic").equals(after.getString("Semantic")))) return null;
        if (domain.equals("setting") && (row.type != ActionType.ME_SETTING_CHANGE || !before.getString("Setting").equals(after.getString("Setting")))) return null;
        return new Pair(before, after);
    }
    private static boolean valid(CompoundTag tag) {
        if (tag == null || !tag.getString("Kind").equals("Ae2Configuration") || tag.getInt("Version") != 1) return false;
        if (tag.getString("Domain").equals("setting"))
            return !tag.getString("Setting").isBlank() && tag.contains("Present", Tag.TAG_BYTE)
                    && (tag.getBoolean("Present") ? tag.contains("Value", Tag.TAG_STRING) : !tag.contains("Value"));
        if (!tag.getString("Domain").equals("slot") || !tag.contains("Slot", Tag.TAG_INT) || tag.getInt("Slot") < 0
                || tag.getString("Semantic").isBlank() || !tag.contains("Amount", Tag.TAG_LONG)) return false;
        try {
            var resource = new Resource(tag.getString("ResourceKind"), tag.getString("Resource"), tag.getLong("Amount"));
            return resource.kind().equals("empty") ? !tag.contains("Item") : tag.contains("Item", Tag.TAG_COMPOUND)
                    && tag.getCompound("Item").contains("id", Tag.TAG_STRING);
        } catch (RuntimeException invalid) { return false; }
    }
    public static List<Component> describe(LogEntry row) {
        var pair = context(row); if (pair == null) return List.of();
        var old = pair.before(); var now = pair.after();
        if (old.getString("Domain").equals("setting")) return List.of(
                Component.literal("Параметр МЭ: " + old.getString("Setting")),
                Component.literal("До: " + value(old)), Component.literal("После: " + value(now)));
        return List.of(Component.literal("Слот МЭ: " + old.getInt("Slot") + " · " + old.getString("Semantic")),
                Component.literal("До: " + resource(old).label()), Component.literal("После: " + resource(now).label()),
                Component.literal("Полные состояния предмета и его компонентов сохранены для обеих сторон"));
    }
    private static String value(CompoundTag tag) {
        String value = tag.getBoolean("Present") ? tag.getString("Value") : "отсутствует";
        return value.length() <= 512 ? value : value.substring(0, 512) + "…";
    }
    private static Resource resource(CompoundTag tag) { return new Resource(tag.getString("ResourceKind"), tag.getString("Resource"), tag.getLong("Amount")); }
}
