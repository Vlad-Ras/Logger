package com.roften.avilixlogger.core;

/**
 * Server-authoritative, allocation-free hot-path policy for the twelve GUI
 * type presets. An enabled bit means the corresponding capture is DISABLED.
 * Query/display filters are deliberately not modified.
 *
 * Some GUI queries include generic historical events (e.g. plane filter
 * admits ENTITY_SPAWN). At capture time the source distinguishes actual
 * planes from ordinary entities, avoiding accidental global suppression.
 */
public final class CaptureTypePolicy {
    public static final int GROUPS = 12;
    public static final int ALL_DISABLED = (1 << GROUPS) - 1;
    private static volatile int disabledMask;
    private CaptureTypePolicy() {}

    public static int mask() { return disabledMask; }
    public static void install(int mask) { disabledMask = mask & ALL_DISABLED; }
    public static boolean enabled(int preset) {
        return preset >= 1 && preset <= GROUPS && (disabledMask & (1 << (preset - 1))) == 0;
    }
    public static int changed(int mask, int preset, boolean enabled) {
        if (preset < 1 || preset > GROUPS) throw new IllegalArgumentException("Invalid GUI type preset");
        int bit = 1 << (preset - 1);
        return enabled ? mask & ~bit : mask | bit;
    }

    public static boolean allows(ActionType type, String source, String extra) {
        if (type == null || disabledMask == 0 || type == ActionType.CART_ROLLBACK) return true;
        // Filter specific source families before generic action categories.
        if (source != null) {
            if (source.startsWith("create:train:") || source.equals("create:train"))
                if (!enabled(5)) return false;
            if (source.startsWith("create:cart:") && !enabled(9)) return false;
            if (source.startsWith("create:bearing:") && !enabled(11)) return false;
            if (source.startsWith("create:contraption:") && !enabled(12)) return false;
            if ((source.startsWith("plane:") || source.startsWith("mod:immersive_aircraft")
                    || source.startsWith("mod:man_of_many_planes")) && !enabled(8)) return false;
        }
        if (type.name().startsWith("TRAIN_") && !enabled(5)) return false;
        if (type.name().startsWith("CART_") && !enabled(9)
                && (source == null || source.startsWith("create:cart:"))) return false;
        if (type.name().startsWith("BACKPACK_") && !enabled(10)) return false;
        if (type.name().startsWith("PLANE_") && !enabled(8)) return false;
        if (type == ActionType.CHAT_MESSAGE && !enabled(7)) return false;
        if (!enabled(6) && extra != null && extra.toLowerCase(java.util.Locale.ROOT).contains("cannon")) return false;
        return switch (type) {
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_USE ->
                    enabled(1);
            case BLOCK_ENTITY_NBT_CHANGE -> enabled(1) && enabled(2);
            case CONTAINER_OPEN, ENTITY_CONTAINER_OPEN, CONTAINER_PUT, CONTAINER_TAKE,
                    ME_PUT, ME_TAKE, ME_UPGRADE_CHANGE, ME_FILTER_CHANGE,
                    ME_SETTING_CHANGE, ME_CELL_CHANGE -> enabled(2);
            case GUI_OPEN -> enabled(2) && enabled(3);
            case GUI_CHANGE -> enabled(2);
            case ENTITY_SPAWN, ENTITY_DEATH, ENTITY_MOUNT, ENTITY_DISMOUNT,
                    ENTITY_INTERACT, ENTITY_ATTACK, ENTITY_EQUIPMENT_CHANGE, WEAPON_KILL, PLAYER_DEATH,
                    PLAYER_DIMENSION_CHANGE, PLAYER_RESPAWN -> enabled(3);
            case PROJECTILE_SHOOT, PROJECTILE_HIT -> enabled(3) && enabled(4);
            case ITEM_GIFT, ITEM_TRADE, WEAPON_RELOAD, ITEM_DROP, ITEM_PICKUP, ITEM_CRAFT, ITEM_TRANSFORM, ITEM_SMELT,
                    ITEM_USE, ITEM_USE_START, ITEM_USE_STOP, ITEM_CONSUME -> enabled(4);
            default -> true;
        };
    }

    /** Early source-only gate before serializing any block/entity NBT. */
    public static boolean allowsCreateKind(CreateContraptionKind kind) {
        return switch (kind) {
            case TRAIN -> enabled(5);
            case MINECART -> enabled(9);
            case BEARING -> enabled(11);
            case OTHER -> enabled(12);
            case UNKNOWN -> true;
        };
    }
}
