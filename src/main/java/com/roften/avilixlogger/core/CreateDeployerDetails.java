package com.roften.avilixlogger.core;

import java.util.List;
import java.util.ArrayList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

/** Reads detached metadata; the optional Create mod is not required by history or GUI code. */
public final class CreateDeployerDetails {
    private CreateDeployerDetails() {}
    public static String resultLabel(LogEntry row) {
        var after = NbtSerde.fromSnbt(row.beAfter);
        return after == null ? "активатор Create" : resultLabel(after.getString("Result"));
    }
    private static String resultLabel(String value) {
        return switch (value) {
            case "block_use" -> "использование блока";
            case "item_use", "item_first" -> "использование предмета";
            case "entity_use" -> "взаимодействие с сущностью";
            case "player_feed" -> "кормление игрока";
            case "attack" -> "удар по сущности";
            case "harvest" -> "разрушение блока";
            default -> "изменение блока или предмета";
        };
    }
    public static List<Component> describe(ServerLevel level, LogEntry row) {
        if (row.type != ActionType.CREATE_DEPLOYER_ACTION) return List.of();
        var before = NbtSerde.fromSnbt(row.beBefore); var after = NbtSerde.fromSnbt(row.beAfter);
        if (before == null || after == null || !after.getString("Kind").equals("deployer_action"))
            return List.of(Component.literal("Действие активатора: подробный снимок отсутствует"));
        var lines = new ArrayList<Component>();
        lines.add(Component.literal("Активатор: " + (after.getString("Mode").equals("PUNCH") ? "атака" : "использование") + " · " + resultLabel(after.getString("Result"))));
        lines.add(Component.literal("Предмет в руке: ").append(item(level, before.getCompound("HeldItem"))).append(" → ").append(item(level, after.getCompound("HeldItem"))));
        if (!before.getCompound("HeldItem").getCompound("components").equals(after.getCompound("HeldItem").getCompound("components")))
            lines.add(Component.literal("Компоненты предмета изменились; полные значения сохранены в снимке"));
        if (after.contains("Damage")) lines.add(Component.literal("Потеря здоровья и поглощения: " + after.getFloat("Damage")));
        if (row.entityUuid != null) lines.add(Component.literal("Цель: ").append(EntityLogIdentity.label(row)));
        lines.add(Component.literal("Владелец: " + (after.hasUUID("Owner") ? after.getUUID("Owner") + " · " + (after.getString("OwnerSource").equals("deployer") ? "владелец активатора" : "владелец вагонеточной конструкции") : "неизвестен")));
        if (!after.getBoolean("Complete") || before.getBoolean("Incomplete") || after.getBoolean("Incomplete"))
            lines.add(Component.literal("Неполное действие или снимок; автоматический откат не применяется"));
        return List.copyOf(lines);
    }
    private static Component item(ServerLevel level, CompoundTag tag) {
        if (tag.isEmpty()) return Component.literal("пусто");
        if (level != null) {
            var item = NbtSerde.readItemStack(tag, level.registryAccess());
            if (!item.isEmpty()) return item.getHoverName().copy().append(" ×" + item.getCount());
        }
        return Component.literal(tag.getString("id") + " ×" + (tag.contains("count") ? tag.getInt("count") : 1));
    }
}
