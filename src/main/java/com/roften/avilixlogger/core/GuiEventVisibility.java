package com.roften.avilixlogger.core;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.roften.avilixlogger.core.ActionType.*;

/** Personal journal exclusions. Rules use saved fields and are independent of capture policy. */
public record GuiEventVisibility(long categories, Set<ActionType> actions) {
    public static final GuiEventVisibility ALL_VISIBLE = new GuiEventVisibility(0, Set.of());
    private static final List<Category> CATEGORIES = buildCategories();
    private static final long CATEGORY_MASK = CATEGORIES.stream().mapToLong(c -> bit(c.preset())).reduce(0, (a, b) -> a | b);

    public record Category(int preset, Set<ActionType> actions, List<String> prefixes, List<String> markers) {}

    public GuiEventVisibility {
        actions = Set.copyOf(actions);
    }

    public static List<Category> supportedCategories() { return CATEGORIES; }

    public static GuiEventVisibility of(long categories, Set<ActionType> actions) {
        return new GuiEventVisibility(categories & CATEGORY_MASK, actions);
    }

    public boolean active() { return categories != 0 || !actions.isEmpty(); }
    public boolean hides(int preset) { return (categories & bit(preset)) != 0; }
    public GuiEventVisibility toggle(int preset) { return of(categories ^ bit(preset), actions); }

    public GuiEventVisibility toggle(ActionType action) {
        var next = EnumSet.noneOf(ActionType.class);
        next.addAll(actions);
        if (!next.remove(action)) next.add(action);
        return of(categories, next);
    }

    private static long bit(int preset) { return 1L << (preset - 1); }

    private static Category group(int preset, ActionType... actions) {
        return new Category(preset, Set.of(actions), List.of(), List.of());
    }

    private static Category source(int preset, Set<ActionType> actions, String... prefixes) {
        return new Category(preset, actions, List.of(prefixes), List.of());
    }

    private static Set<ActionType> family(String prefix) {
        return java.util.Arrays.stream(ActionType.values()).filter(action -> action.name().startsWith(prefix)).collect(Collectors.toUnmodifiableSet());
    }

    private static Category mechanism(int preset, String... blockIds) {
        var markers = java.util.Arrays.stream(blockIds).map(id -> ":mechanism=" + id + "@").toList();
        return new Category(preset, preset == 31 ? Set.of(CREATE_DEPLOYER_ACTION) : Set.of(), List.of(), markers);
    }

    private static List<Category> buildCategories() {
        var values = new ArrayList<>(List.of(
                group(1, BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_USE, BLOCK_ENTITY_NBT_CHANGE,
                        CLAIM_ACTION, PROTECTION_CHANGE, MACHINE_STATE_CHANGE),
                group(2, CONTAINER_OPEN, ENTITY_CONTAINER_OPEN, CONTAINER_PUT, CONTAINER_TAKE, BLOCK_ENTITY_NBT_CHANGE,
                        GUI_OPEN, GUI_CHANGE, CORPSE_CONTENT_CHANGE, MACHINE_CONTENT_CHANGE, EXPERIENCE_CHANGE,
                        ME_PUT, ME_TAKE, ME_UPGRADE_CHANGE, ME_FILTER_CHANGE, ME_SETTING_CHANGE, ME_CELL_CHANGE,
                        ME_CRAFT_REQUEST, ME_CRAFT_CANCEL, ME_CRAFT_FINISH),
                group(3, ENTITY_SPAWN, ENTITY_DEATH, ENTITY_MOUNT, ENTITY_DISMOUNT, ENTITY_INTERACT, ENTITY_ATTACK,
                        ENTITY_EQUIPMENT_CHANGE, PARTY_CHANGE, GUI_OPEN, PROJECTILE_SHOOT, PROJECTILE_HIT,
                        WEAPON_KILL, PLAYER_JOIN, PLAYER_LEAVE, PLAYER_DIMENSION_CHANGE, PLAYER_RESPAWN),
                group(4, ITEM_GIFT, ITEM_TRADE, WEAPON_RELOAD, ITEM_DROP, ITEM_PICKUP, ITEM_CRAFT, ITEM_TRANSFORM,
                        ITEM_SMELT, ITEM_USE, ITEM_USE_START, ITEM_USE_STOP, ITEM_CONSUME, PROJECTILE_SHOOT, PROJECTILE_HIT),
                source(5, family("TRAIN_"), "create:train:"),
                group(6),
                group(7, CHAT_MESSAGE),
                source(8, Set.of(PLANE_PLACE, PLANE_REMOVE, PLANE_MOUNT, PLANE_PICKUP, ENTITY_OWNER_SET),
                        "plane:", "mod:immersive_aircraft", "mod:man_of_many_planes"),
                source(9, Set.of(), "create:cart:"),
                source(10, family("BACKPACK_"), "backpacked:bag:"),
                source(11, Set.of(), "create:bearing:"),
                source(12, Set.of(), "create:contraption:")));
        for (var module : ModLogFilters.MODULES) {
            values.add(source(module.preset(), module.preset() == 13 ? family("ME_") : Set.of(), module.sourcePrefix()));
        }
        values.addAll(List.of(
                new Category(26, family("ME_CRAFT_"), List.of(), List.of()),
                group(27, ME_PUT, ME_TAKE),
                group(28, ME_UPGRADE_CHANGE, ME_FILTER_CHANGE, ME_SETTING_CHANGE, ME_CELL_CHANGE),
                group(29, PLAYER_DEATH, PLAYER_KILL),
                mechanism(31, "create:deployer"),
                mechanism(32, "create:mechanical_drill"),
                mechanism(33, "create:mechanical_saw"),
                mechanism(34, "create:mechanical_harvester"),
                mechanism(35, "create:mechanical_plough"),
                mechanism(36, "create:mechanical_roller"),
                mechanism(37, "create:chute", "create:smart_chute"),
                mechanism(38, "create:andesite_funnel", "create:brass_funnel", "create:andesite_belt_funnel", "create:brass_belt_funnel"),
                mechanism(39, "create:portable_storage_interface", "create:portable_fluid_interface"),
                mechanism(40, "minecraft:dispenser", "minecraft:dropper")));
        return List.copyOf(values);
    }

    public boolean hidden(ActionType action, String source, String extra) {
        if (!active()) return false;
        if (action != null && actions.contains(action)) return true;
        String origin = source == null ? "" : source;
        for (var category : CATEGORIES) {
            if (!hides(category.preset())) continue;
            if (action != null && category.actions().contains(action)) return true;
            if (category.prefixes().stream().anyMatch(prefix -> matchesPrefix(origin, prefix))) return true;
            if (category.markers().stream().anyMatch(origin::contains)) return true;
            if (category.preset() == 6 && extra != null && extra.toLowerCase(Locale.ROOT).contains("cannon")) return true;
        }
        return false;
    }

    private static boolean matchesPrefix(String value, String prefix) {
        return value.startsWith(prefix) || (prefix.endsWith(":") && value.equals(prefix.substring(0, prefix.length() - 1)));
    }

    public void appendWhere(StringBuilder sql, Function<String, String> quote, String extraColumn) {
        if (!active()) return;
        var clauses = new ArrayList<String>();
        var hiddenTypes = EnumSet.noneOf(ActionType.class);
        hiddenTypes.addAll(actions);
        for (var category : CATEGORIES) {
            if (!hides(category.preset())) continue;
            hiddenTypes.addAll(category.actions());
            for (String prefix : category.prefixes()) {
                clauses.add("startsWith(source, " + quote.apply(prefix) + ")");
                if (prefix.endsWith(":")) clauses.add("source = " + quote.apply(prefix.substring(0, prefix.length() - 1)));
            }
            for (String marker : category.markers()) clauses.add("position(source, " + quote.apply(marker) + ") > 0");
            if (category.preset() == 6) clauses.add("positionCaseInsensitiveUTF8(" + extraColumn + ", 'cannon') > 0");
        }
        if (!hiddenTypes.isEmpty()) {
            clauses.add("action IN (" + hiddenTypes.stream().map(type -> Integer.toString(type.ordinal())).collect(Collectors.joining(",")) + ")");
        }
        if (!clauses.isEmpty()) sql.append(" AND NOT (").append(String.join(" OR ", clauses)).append(')');
    }
}
