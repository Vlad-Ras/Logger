package com.roften.avilixlogger.core;

/**
 * Server-authoritative, allocation-free hot-path policy for the GUI
 * capture controls. An enabled bit means the corresponding capture is DISABLED.
 * Query/display filters are deliberately not modified.
 *
 * Some GUI queries include generic historical events (e.g. plane filter
 * admits ENTITY_SPAWN). At capture time the source distinguishes actual
 * planes from ordinary entities, avoiding accidental global suppression.
 */
public final class CaptureTypePolicy {
    // IDs 1..30 are already persisted by 2.1.28. Never reuse them for mechanisms.
    public static final int GROUPS = 40;
    public static final int CREATE_DEPLOYER = 31, CREATE_DRILL = 32, CREATE_SAW = 33,
            CREATE_HARVESTER = 34, CREATE_PLOUGH = 35, CREATE_ROLLER = 36,
            CREATE_CHUTE = 37, CREATE_FUNNEL = 38, CREATE_PORTABLE_STORAGE = 39,
            CREATE_DISPENSER = 40;
    public static final long ALL_DISABLED = (1L << GROUPS) - 1;
    private static volatile long disabledMask;
    private static volatile long generation;
    private static final ThreadLocal<Boolean> SUPPRESSED = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private CaptureTypePolicy() {}

    public static long mask() { return disabledMask; }
    public static void install(long mask) {
        long next = mask & ALL_DISABLED;
        if (disabledMask != next) { disabledMask = next; generation++; }
    }
    static void handlersChanged() { generation++; }
    public static long generation() { return generation; }
    public static boolean suppressed() { return Boolean.TRUE.equals(SUPPRESSED.get()); }
    /** Nested callbacks may suppress capture, but cannot re-enable an outer muted operation. */
    public static boolean suppress(boolean value) {
        boolean previous = suppressed();
        if (value && !previous) SUPPRESSED.set(Boolean.TRUE);
        return previous;
    }
    public static void restoreSuppression(boolean previous) {
        if (suppressed() != previous) SUPPRESSED.set(previous);
    }
    public static void clearSuppression() { SUPPRESSED.remove(); }
    public static boolean enabled(int preset) {
        return preset >= 1 && preset <= GROUPS && (disabledMask & (1L << (preset - 1))) == 0;
    }
    public static long changed(long mask, int preset, boolean enabled) {
        if (preset < 1 || preset > GROUPS) throw new IllegalArgumentException("Invalid GUI type preset");
        long bit = 1L << (preset - 1);
        return enabled ? mask & ~bit : mask | bit;
    }

    public static boolean allows(ActionType type, String source, String extra) {
        if (type == ActionType.PLAYER_DEATH || type == ActionType.PLAYER_KILL) return enabled(29);
        if(type==ActionType.CART_ROLLBACK || type==ActionType.CAPTURE_GAP)return true;
        if (suppressed() || !ServerCaptureGuard.captureAllowed()) return false;
        if (type == null || disabledMask == 0 || type == ActionType.CART_ROLLBACK) return true;
        // Filter specific source families before generic action categories.
        if (source != null) {
            for (int i=0;i<ModLogFilters.MODULES.size();i++) {
                var module=ModLogFilters.MODULES.get(i);var prefix=module.sourcePrefix();
                if ((source.startsWith(prefix) || source.length()==prefix.length()-1 && prefix.startsWith(source)) && !enabled(module.preset())) return false;
            }
            if (source.startsWith("create:train:") || source.equals("create:train"))
                if (!enabled(5)) return false;
            if (source.startsWith("create:cart:") && !enabled(9)) return false;
            if (source.startsWith("create:bearing:") && !enabled(11)) return false;
            if (source.startsWith("create:contraption:") && !enabled(12)) return false;
            if ((source.startsWith("plane:") || source.startsWith("mod:immersive_aircraft")
                    || source.startsWith("mod:man_of_many_planes")) && !enabled(8)) return false;
        }
        if (type.name().startsWith("ME_")) {
            if (!enabled(13)) return false;
            if (type.name().startsWith("ME_CRAFT_") && !enabled(26)) return false;
            if ((type == ActionType.ME_PUT || type == ActionType.ME_TAKE) && !enabled(27)) return false;
            if (!(type.name().startsWith("ME_CRAFT_") || type == ActionType.ME_PUT || type == ActionType.ME_TAKE) && !enabled(28)) return false;
        }
        if (type.name().startsWith("TRAIN_") && !enabled(5)) return false;
        if (type.name().startsWith("CART_") && !enabled(9)
                && (source == null || source.startsWith("create:cart:"))) return false;
        if (type.name().startsWith("BACKPACK_") && !enabled(10)) return false;
        if (type.name().startsWith("PLANE_") && !enabled(8)) return false;
        if (type == ActionType.CHAT_MESSAGE && !enabled(7)) return false;
        if (!enabled(6) && extra != null && extra.toLowerCase(java.util.Locale.ROOT).contains("cannon")) return false;
        return switch (type) {
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_INTERACT, BLOCK_USE, CLAIM_ACTION, PROTECTION_CHANGE, MACHINE_STATE_CHANGE ->
                    enabled(1);
            case BLOCK_ENTITY_NBT_CHANGE -> enabled(1) && enabled(2);
            case CONTAINER_OPEN, ENTITY_CONTAINER_OPEN, CONTAINER_PUT, CONTAINER_TAKE,
                    ME_PUT, ME_TAKE, ME_UPGRADE_CHANGE, ME_FILTER_CHANGE,
                    ME_SETTING_CHANGE, ME_CELL_CHANGE, ME_CRAFT_REQUEST, ME_CRAFT_CANCEL, ME_CRAFT_FINISH -> enabled(2);
            case GUI_OPEN -> enabled(2) && enabled(3);
            case GUI_CHANGE, CORPSE_CONTENT_CHANGE, MACHINE_CONTENT_CHANGE, EXPERIENCE_CHANGE -> enabled(2);
            case ENTITY_SPAWN, ENTITY_DEATH, ENTITY_MOUNT, ENTITY_DISMOUNT,
                    ENTITY_INTERACT, ENTITY_ATTACK, ENTITY_EQUIPMENT_CHANGE, PARTY_CHANGE, WEAPON_KILL, PLAYER_DEATH,
                    PLAYER_DIMENSION_CHANGE, PLAYER_RESPAWN -> enabled(3);
            case PROJECTILE_SHOOT, PROJECTILE_HIT -> enabled(3) && enabled(4);
            case ITEM_GIFT, ITEM_TRADE, WEAPON_RELOAD, ITEM_DROP, ITEM_PICKUP, ITEM_CRAFT, ITEM_TRANSFORM, ITEM_SMELT,
                    ITEM_USE, ITEM_USE_START, ITEM_USE_STOP, ITEM_CONSUME -> enabled(4);
            default -> true;
        };
    }

    public static String title(int preset) {
        var module = ModLogFilters.find(preset);
        if (module != null) return "Мод: " + module.title();
        return switch (preset) {
            case 26 -> "AE2: автокрафт (запрос / отмена / завершение)";
            case 27 -> "AE2: добавление и извлечение ресурсов";
            case 28 -> "AE2: настройки, фильтры, ячейки и улучшения";
            case 29 -> "Смерти и убийства игроков";
            case 30 -> "Переполнение: пауза записи для защиты TPS";
            case CREATE_DEPLOYER -> "Create: активаторы на движущихся штуковинах";
            case CREATE_DRILL -> "Create: буры на движущихся штуковинах";
            case CREATE_SAW -> "Create: пилы на движущихся штуковинах";
            case CREATE_HARVESTER -> "Create: комбайны на движущихся штуковинах";
            case CREATE_PLOUGH -> "Create: плуги на движущихся штуковинах";
            case CREATE_ROLLER -> "Create: катки на движущихся штуковинах";
            case CREATE_CHUTE -> "Create: желоба и умные желоба";
            case CREATE_FUNNEL -> "Create: воронки";
            case CREATE_PORTABLE_STORAGE -> "Create: переносные интерфейсы";
            case CREATE_DISPENSER -> "Create: раздатчики на штуковинах";
            default -> null;
        };
    }

    /** Early source-only gate before serializing any block/entity NBT. */
    public static boolean allowsCreateKind(CreateContraptionKind kind) {
        if(!ServerCaptureGuard.captureAllowed())return false;
        return switch (kind) {
            case TRAIN -> enabled(5);
            case MINECART -> enabled(9);
            case BEARING -> enabled(11);
            case OTHER -> enabled(12);
            case UNKNOWN -> true;
        };
    }
}
