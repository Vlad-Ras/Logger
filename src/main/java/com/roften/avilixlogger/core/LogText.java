package com.roften.avilixlogger.core;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

/**
 * Formatting utilities for log entries.
 *
 * Requirements:
 * - Block subject must display only block name (not full BlockState SNBT).
 * - Item actions must display item name and count.
 * - Coordinates must be clickable and allow teleporting.
 */
public final class LogText {
    private LogText() {}

    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("dd.MM HH:mm:ss");

    private static final Pattern SNBT_NAME_QUOTED = Pattern.compile("Name\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern SNBT_NAME_BARE = Pattern.compile("Name\\s*:\\s*([a-z0-9_\\-\\.]+:[a-z0-9_\\-\\.]+)");
    private static final Pattern ITEM_ID_QUOTED = Pattern.compile("id\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ITEM_ID_BARE = Pattern.compile("id\\s*:\\s*([a-z0-9_\\-\\.]+:[a-z0-9_\\-\\.]+)");

    /** Backward-compatible: renders without registry-based translations. */
    public static Component toChatLine(LogEntry e) {
        return toChatLine(null, e);
    }

    public static Component toChatLine(ServerLevel level, LogEntry e) {
        if (e == null) return Component.empty();

        String ts = TIME_FMT.format(Instant.ofEpochMilli(e.ts).atZone(ZONE));
        MutableComponent c = Component.empty();
        c.append(Component.literal("[" + ts + "] ").withStyle(ChatFormatting.DARK_GRAY));

        // New player-death rows persist the exact vanilla/modded death text produced by the combat
        // tracker. Render that text directly instead of rebuilding it from killer/victim fields.
        if (e.type == ActionType.PLAYER_DEATH && e.extra != null && e.extra.startsWith("death_message:")) {
            String deathMessage = e.extra.substring("death_message:".length()).trim();
            if (!deathMessage.isBlank()) {
                c.append(Component.literal(deathMessage).withStyle(ChatFormatting.DARK_RED));
                c.append(Component.literal(" (").withStyle(ChatFormatting.DARK_GRAY));
                c.append(coordComponent(e));
                c.append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));
                return c;
            }
        }

        // Actor attribution is not always possible for server-side actions (worldgen, automation, async spawns).
        // In such cases, show a neutral "server" actor instead of "?" to reduce noise without blaming players.
        boolean hasActor = e.actorName != null && !e.actorName.isBlank();
        String who = hasActor ? e.actorName : "Сервер";

        MutableComponent whoComp = Component.literal(who)
                .withStyle(hasActor ? ChatFormatting.AQUA : ChatFormatting.DARK_GRAY);

        if (e.actorUuid != null && hasActor) {
            whoComp = whoComp.withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    Component.literal("UUID: " + e.actorUuid).withStyle(ChatFormatting.GRAY))));
            // удобнее быстро подставлять ник в команду
            whoComp = whoComp.withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/log lookup 10m " + who)));
        }
        c.append(whoComp);
        c.append(Component.literal(" "));

        // Action label
        c.append(Component.literal(actionLabel(e.type)).withStyle(actionColor(e.type)));
        c.append(Component.literal(" "));

        // Subject
        MutableComponent subj = subjectComponent(level, e);
        if (subj != null) {
            c.append(subj.withStyle(subjectColor(e.type)));
            // Count directly in the main line for item actions
            if (isItemType(e.type) && e.count > 0) {
                c.append(Component.literal(" x" + e.count).withStyle(ChatFormatting.GRAY));
            }

            // For container put/take, show where the change happened.
            if (level != null && (e.type == ActionType.CONTAINER_PUT || e.type == ActionType.CONTAINER_TAKE)) {
                MutableComponent where = blockNameComponent(level, e.blockAfter);
                if (where != null) {
                    c.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
                    c.append(Component.literal(e.type == ActionType.CONTAINER_PUT ? "в " : "из ").withStyle(ChatFormatting.GRAY));
                    c.append(where.withStyle(ChatFormatting.YELLOW));
                }
            }
            c.append(Component.literal(" "));
        }

        // Coordinates (clickable tp)
        c.append(Component.literal("(").withStyle(ChatFormatting.DARK_GRAY));
        c.append(coordComponent(e));
        c.append(Component.literal(")").withStyle(ChatFormatting.DARK_GRAY));

        // For container snapshot entries, append a short + / - summary directly in the main line.
        try {
            if (level != null
                    && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE
                    && e.extra != null && e.extra.startsWith("container change")
                    && e.containerSlotsBefore != null && e.containerSlotsAfter != null
                    && !e.containerSlotsBefore.isBlank() && !e.containerSlotsAfter.isBlank()) {
                var agg = ContainerSlotDiffUtil.diffAggregated(e.containerSlotsBefore, e.containerSlotsAfter, level.registryAccess());
                if (agg != null && !agg.isEmpty()) {
                    java.util.List<String> plus = new java.util.ArrayList<>();
                    java.util.List<String> minus = new java.util.ArrayList<>();
                    for (var ent : agg.entrySet()) {
                        int delta = ent.getValue();
                        if (delta == 0) continue;
                        String name = safeItemNamePlain(level, ent.getKey());
                        if (delta > 0) plus.add("+" + delta + " " + name);
                        else minus.add(delta + " " + name);
                    }
                    int maxEach = 3;
                    if (!plus.isEmpty() || !minus.isEmpty()) {
                        c.append(Component.literal(" ").withStyle(ChatFormatting.DARK_GRAY));
                        int shown = 0;
                        for (int i = 0; i < plus.size() && i < maxEach; i++) {
                            if (shown++ > 0) c.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                            c.append(Component.literal(plus.get(i)).withStyle(ChatFormatting.GREEN));
                        }
                        for (int i = 0; i < minus.size() && i < maxEach; i++) {
                            if (shown++ > 0) c.append(Component.literal(", ").withStyle(ChatFormatting.DARK_GRAY));
                            c.append(Component.literal(minus.get(i)).withStyle(ChatFormatting.RED));
                        }
                        int rest = Math.max(0, (plus.size() - maxEach)) + Math.max(0, (minus.size() - maxEach));
                        if (rest > 0) {
                            c.append(Component.literal(" …" + rest).withStyle(ChatFormatting.DARK_GRAY));
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // Optional attribution hint (e.g. Create devices)
        if (e.source != null && !e.source.isBlank()) {
            c.append(Component.literal(" ").withStyle(ChatFormatting.GRAY));
            c.append(Component.literal("[via " + e.source + "]").withStyle(ChatFormatting.DARK_GRAY));
        }
        return c;
    }

    private static String safeItemNamePlain(ServerLevel level, String itemStackSnbt) {
        try {
            MutableComponent c = itemNameComponent(level, itemStackSnbt);
            return c == null ? "?" : c.getString();
        } catch (Throwable t) {
            return "?";
        }
    }

    private static boolean isItemType(ActionType t) {
        return t == ActionType.CONTAINER_PUT
                || t == ActionType.CONTAINER_TAKE
                || t == ActionType.ITEM_PICKUP
                || t == ActionType.ITEM_DROP
                || t == ActionType.ITEM_CRAFT
                || t == ActionType.ITEM_SMELT
                || t == ActionType.ITEM_USE
                || t == ActionType.ITEM_USE_START
                || t == ActionType.ITEM_USE_STOP
                || t == ActionType.ITEM_CONSUME;
    }

    private static ChatFormatting actionColor(ActionType t) {
        if (t == null) return ChatFormatting.GRAY;
        return switch (t) {
            case BLOCK_BREAK -> ChatFormatting.RED;
            case BLOCK_PLACE -> ChatFormatting.GREEN;
            case BLOCK_INTERACT, BLOCK_USE, CONTAINER_OPEN -> ChatFormatting.GOLD;
            case BLOCK_ENTITY_NBT_CHANGE -> ChatFormatting.YELLOW;

            case CONTAINER_PUT, ITEM_PICKUP, ITEM_CRAFT, ITEM_SMELT, ITEM_CONSUME, ITEM_USE_START, PROJECTILE_SHOOT -> ChatFormatting.GREEN;
            case CONTAINER_TAKE, ITEM_DROP, ITEM_USE_STOP -> ChatFormatting.RED;

            case PLAYER_JOIN, PLAYER_RESPAWN, PLAYER_DIMENSION_CHANGE, GUI_OPEN -> ChatFormatting.GREEN;
            case PLAYER_LEAVE -> ChatFormatting.RED;
            case PLAYER_DEATH, ENTITY_DEATH -> ChatFormatting.DARK_RED;

            case ENTITY_SPAWN -> ChatFormatting.LIGHT_PURPLE;
            case ENTITY_INTERACT, ENTITY_ATTACK, PROJECTILE_HIT -> ChatFormatting.GOLD;
            case ENTITY_OWNER_SET -> ChatFormatting.YELLOW;
            case CHAT_MESSAGE -> ChatFormatting.AQUA;

            case TRAIN_ASSEMBLE -> ChatFormatting.GREEN;
            case TRAIN_DISASSEMBLE -> ChatFormatting.RED;
            case TRAIN_SCHEDULE_TAKE -> ChatFormatting.GOLD;
            case TRAIN_CONTROL_START -> ChatFormatting.AQUA;
            case TRAIN_CONTROL_STOP -> ChatFormatting.GRAY;
            case TRAIN_SCHEDULE_PUT -> ChatFormatting.GOLD;

            default -> ChatFormatting.GRAY;
        };
    }

    private static ChatFormatting subjectColor(ActionType t) {
        if (t == null) return ChatFormatting.WHITE;
        return switch (t) {
            case ENTITY_DEATH, ENTITY_SPAWN -> ChatFormatting.LIGHT_PURPLE;
            default -> ChatFormatting.WHITE;
        };
    }

    private static String actionLabel(ActionType t) {
        if (t == null) return "сделал";
        return switch (t) {
            case BLOCK_BREAK -> "сломал";
            case BLOCK_PLACE -> "поставил";
            case BLOCK_INTERACT -> "изменил через использование";
            case BLOCK_USE -> "использовал";
            case BLOCK_ENTITY_NBT_CHANGE -> "изменил";
            case CONTAINER_OPEN -> "открыл";

            case CONTAINER_PUT -> "положил";
            case CONTAINER_TAKE -> "достал";

            case ITEM_PICKUP -> "подобрал";
            case ITEM_DROP -> "выбросил";
            case ITEM_CRAFT -> "скрафтил";
            case ITEM_SMELT -> "переплавил";
            case ITEM_USE -> "использовал";
            case ITEM_USE_START -> "начал использовать";
            case ITEM_USE_STOP -> "отпустил/прервал";
            case ITEM_CONSUME -> "съел/выпил";
            case PROJECTILE_SHOOT -> "выстрелил";
            case PROJECTILE_HIT -> "попал";

            case ENTITY_DEATH -> "убил";
            case ENTITY_SPAWN -> "заспавнил";
            case ENTITY_MOUNT -> "сел";
            case ENTITY_DISMOUNT -> "вышел";
            case ENTITY_CONTAINER_OPEN -> "открыл";
            case ENTITY_INTERACT -> "взаимодействовал с";
            case ENTITY_ATTACK -> "ударил";
            case ENTITY_OWNER_SET -> "сменил владельца";

            case PLANE_PLACE -> "поставил самолёт";
            case PLANE_REMOVE -> "убрал самолёт";
            case PLANE_MOUNT -> "сел в самолёт";
            case PLANE_PICKUP -> "подобрал самолёт";

            case PLAYER_DEATH -> "умер";
            case PLAYER_JOIN -> "вошёл";
            case PLAYER_LEAVE -> "вышел";
            case PLAYER_DIMENSION_CHANGE -> "сменил измерение";
            case PLAYER_RESPAWN -> "возродился";
            case GUI_OPEN -> "открыл интерфейс";
            case CHAT_MESSAGE -> "написал";

            case TRAIN_ASSEMBLE -> "собрал поезд";
            case TRAIN_DISASSEMBLE -> "разобрал поезд";
            case TRAIN_SCHEDULE_TAKE -> "забрал расписание";
            case TRAIN_CONTROL_START -> "начал управление";
            case TRAIN_CONTROL_STOP -> "закончил управление";
            case TRAIN_SCHEDULE_PUT -> "поставил расписание";

            default -> "сделал";
        };
    }


    private static MutableComponent subjectComponent(ServerLevel level, LogEntry e) {
        if (e == null || e.type == null) return null;

        return switch (e.type) {

            // blocks
            case BLOCK_BREAK -> blockNameComponent(level, e.blockBefore);
            case BLOCK_PLACE, BLOCK_INTERACT, BLOCK_USE, CONTAINER_OPEN, BLOCK_ENTITY_NBT_CHANGE -> blockNameComponent(level, e.blockAfter);

            // container/item diffs
            case CONTAINER_PUT, CONTAINER_TAKE, ITEM_PICKUP, ITEM_DROP, ITEM_CRAFT, ITEM_SMELT, ITEM_USE, ITEM_USE_START, ITEM_USE_STOP, ITEM_CONSUME, PLANE_PICKUP ->
                    itemNameComponent(level, e.itemStackNbt);

            // schedule: и забрал, и поставил показываем предметом
            case TRAIN_SCHEDULE_TAKE, TRAIN_SCHEDULE_PUT ->
                    itemNameComponent(level, e.itemStackNbt);

            // trains: показываем имя поезда из extra.trainName
            case TRAIN_ASSEMBLE, TRAIN_DISASSEMBLE, TRAIN_CONTROL_START, TRAIN_CONTROL_STOP -> {
                String tn = null;
                try { tn = extractJsonString(e.extra, "trainName"); } catch (Throwable ignored) {}
                if (tn == null || tn.isBlank()) tn = "поезд";
                yield Component.literal(tn);
            }

            case PROJECTILE_SHOOT -> {
                MutableComponent src = itemNameComponent(level, e.itemStackNbt);
                yield src != null ? src : Component.literal(e.entityType != null ? e.entityType : "projectile");
            }
            case PROJECTILE_HIT -> Component.literal(e.entityType != null ? e.entityType : "projectile");

            // entities / planes / etc (оставь как у тебя было — ниже максимально безопасный вариант)
            case ENTITY_DEATH, ENTITY_SPAWN, ENTITY_MOUNT, ENTITY_DISMOUNT, ENTITY_CONTAINER_OPEN, ENTITY_INTERACT, ENTITY_ATTACK,
                 PLANE_PLACE, PLANE_REMOVE, PLANE_MOUNT, ENTITY_OWNER_SET -> {
                // если у тебя уже есть логика для самолётов/энтити — можно вернуть её обратно.
                yield Component.literal(e.entityType != null ? e.entityType : "entity");
            }

            // player events / chat / gui
            case GUI_OPEN -> Component.literal(e.extra != null ? e.extra.replaceFirst("^gui_open\\s+", "") : "интерфейс");
            case PLAYER_DEATH, PLAYER_JOIN, PLAYER_LEAVE, PLAYER_DIMENSION_CHANGE, PLAYER_RESPAWN -> null;
            case CHAT_MESSAGE -> Component.literal(e.extra != null ? e.extra : "");

            // ВАЖНО: чтобы компилилось при добавлении новых ActionType в будущем
            default -> null;
        };
    }

    private static MutableComponent coordComponent(LogEntry e) {
        String coords = e.x + " " + e.y + " " + e.z;
        String cmd;
        // Keep dimension if known
        if (e.dim != null && !e.dim.isBlank()) {
            cmd = "/execute in " + e.dim + " run tp @s " + coords;
        } else {
            cmd = "/tp " + coords;
        }

        String coordsFmt = "(" + coords + ")";
        String dimFmt = (e.dim != null && !e.dim.isBlank()) ? e.dim : "unknown";
        MutableComponent hover = Component.literal("Телепортироваться в " + coordsFmt + " в измерении " + dimFmt)
                .withStyle(ChatFormatting.YELLOW);

        return Component.literal(coords).withStyle(ChatFormatting.GRAY)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd)))
                .withStyle(s -> s.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
    }


private static MutableComponent blockNameComponent(ServerLevel level, String blockStateSnbtOrId) {
        String id = extractBlockId(blockStateSnbtOrId);
        if (id == null) id = "?";
        // If we have a level, render translated name; otherwise show id only.
        if (level != null) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                var blk = BuiltInRegistries.BLOCK.get(rl);
                if (blk != null) {
                    return Component.translatable(blk.getDescriptionId());
                }
            }
        }
        return Component.literal(id);
    }

    private static String extractBlockId(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();

        // If it's already a namespaced id
        if (!trimmed.startsWith("{") && trimmed.contains(":") && !trimmed.contains(" ")) {
            return trimmed;
        }

        var m = SNBT_NAME_QUOTED.matcher(trimmed);
        if (m.find()) return m.group(1);

        m = SNBT_NAME_BARE.matcher(trimmed);
        if (m.find()) return m.group(1);

        return null;
    }

    private static MutableComponent itemNameComponent(ServerLevel level, String itemStackSnbt) {
        // Лучший вариант: восстановить ItemStack из SNBT и взять отображаемое имя (локализуется на клиенте)
        if (level != null && itemStackSnbt != null && !itemStackSnbt.isBlank()) {
            ItemStack stack = NbtSerde.readItemStack(itemStackSnbt, level.registryAccess());
            if (stack != null && !stack.isEmpty()) {
                return stack.getHoverName().copy();
            }
        }

        // Фоллбек: по id через реестр
        String id = extractItemId(itemStackSnbt);
        if (id == null) id = "предмет";

        if (level != null) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null) {
                Item it = BuiltInRegistries.ITEM.get(rl);
                if (it != null) {
                    return Component.translatable(it.getDescriptionId());
                }
            }
        }
        return Component.literal(id);
    }

    private static String extractItemId(String itemStackSnbt) {
        if (itemStackSnbt == null) return null;
        try {
            var m = ITEM_ID_QUOTED.matcher(itemStackSnbt);
            if (m.find()) return m.group(1);

            m = ITEM_ID_BARE.matcher(itemStackSnbt);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Very small JSON-string extractor for our extra payloads.
     * We only need: {"key":"value"} style fields.
     */
    private static String extractJsonString(String json, String key) {
        if (json == null || json.isBlank() || key == null || key.isBlank()) return null;
        try {
            String needle = "\"" + key + "\":\"";
            int i = json.indexOf(needle);
            if (i < 0) return null;
            int p = i + needle.length();
            StringBuilder out = new StringBuilder();
            boolean esc = false;
            while (p < json.length()) {
                char c = json.charAt(p++);
                if (esc) {
                    // minimal unescape: \" and \\
                    out.append(c);
                    esc = false;
                    continue;
                }
                if (c == '\\') {
                    esc = true;
                    continue;
                }
                if (c == '"') break;
                out.append(c);
            }
            return out.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
