package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.core.NbtSerde;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

public final class CreateTrainsCompatHooks {

    private CreateTrainsCompatHooks() {}

    public static void logTrainAssembled(ServerLevel level, Object train, BlockPos at) {
        if (!enabled()) return;
        try {
            UUID owner = readUuidField(train, "owner");
            String ownerName = resolvePlayerName(level, owner);

            LogEntry e = base(level, ActionType.TRAIN_ASSEMBLE, owner, ownerName, at);
            fillTrainExtra(e, "train_assemble", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    public static void logTrainDisassembled(ServerLevel level, Object train, UUID actorUuid, String actorName, BlockPos at) {
        if (!enabled()) return;
        try {
            LogEntry e = base(level, ActionType.TRAIN_DISASSEMBLE, actorUuid, actorName, at);
            fillTrainExtra(e, "train_disassemble", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    public static void logScheduleTaken(ServerLevel level, Object train, UUID actorUuid, String actorName, BlockPos at, ItemStack returnedSchedule) {
        if (!enabled()) return;
        try {
            LogEntry e = base(level, ActionType.TRAIN_SCHEDULE_TAKE, actorUuid, actorName, at);

            if (returnedSchedule != null && !returnedSchedule.isEmpty()) {
                e.itemStackNbt = NbtSerde.writeItemStack(returnedSchedule, level.registryAccess());
                e.count = returnedSchedule.getCount();
            }

            fillTrainExtra(e, "train_schedule_take", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    public static void logSchedulePut(ServerLevel level, Object train, UUID actorUuid, String actorName, BlockPos at, ItemStack scheduleItem) {
        if (!enabled()) return;
        try {
            LogEntry e = base(level, ActionType.TRAIN_SCHEDULE_PUT, actorUuid, actorName, at);

            if (scheduleItem != null && !scheduleItem.isEmpty()) {
                e.itemStackNbt = NbtSerde.writeItemStack(scheduleItem, level.registryAccess());
                e.count = scheduleItem.getCount();
            }

            fillTrainExtra(e, "train_schedule_put", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    public static void logControlStart(ServerLevel level, Object train, UUID actorUuid, String actorName, BlockPos at) {
        if (!enabled()) return;
        try {
            LogEntry e = base(level, ActionType.TRAIN_CONTROL_START, actorUuid, actorName, at);
            fillTrainExtra(e, "train_control_start", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    public static void logControlStop(ServerLevel level, Object train, UUID actorUuid, String actorName, BlockPos at) {
        if (!enabled()) return;
        try {
            LogEntry e = base(level, ActionType.TRAIN_CONTROL_STOP, actorUuid, actorName, at);
            fillTrainExtra(e, "train_control_stop", train, null);
            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    private static boolean enabled() {
        try {
            return LoggerConfig.VALUES.enabled.get() && LoggerConfig.VALUES.logEntities.get();
        } catch (Throwable t) {
            return false;
        }
    }

    private static LogEntry base(ServerLevel level, ActionType type, UUID actorUuid, String actorName, BlockPos at) {
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.type = type;

        e.actorUuid = actorUuid;
        e.actorName = (actorName != null && !actorName.isBlank()) ? actorName : (actorUuid != null ? actorUuid.toString() : "UNKNOWN");

        e.source = "create:train";

        e.x = at.getX();
        e.y = at.getY();
        e.z = at.getZ();
        return e;
    }

    private static void fillTrainExtra(LogEntry e, String kind, Object train, String note) {
        UUID trainUuid = readUuidField(train, "id");
        UUID ownerUuid = readUuidField(train, "owner");
        String trainName = safeStr(readTrainName(train));

        e.extra = "{"
                + "\"kind\":\"" + esc(kind) + "\""
                + ",\"trainId\":\"" + esc(safeUuid(trainUuid)) + "\""
                + ",\"ownerUuid\":\"" + esc(safeUuid(ownerUuid)) + "\""
                + ",\"trainName\":\"" + esc(trainName) + "\""
                + ",\"note\":\"" + esc(note == null ? "" : note) + "\""
                + "}";
    }

    private static String readTrainName(Object train) {
        if (train == null) return null;

        // поле Train#name: Component
        Object name = readField(train, "name");
        if (name == null) return null;

        try {
            Method getString = name.getClass().getMethod("getString");
            Object s = getString.invoke(name);
            return s != null ? s.toString() : null;
        } catch (Throwable ignored) {}

        return name.toString();
    }

    private static UUID readUuidField(Object obj, String field) {
        Object v = readField(obj, field);
        return (v instanceof UUID u) ? u : null;
    }

    private static Object readField(Object obj, String field) {
        if (obj == null || field == null) return null;
        Class<?> c = obj.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ex) {
                c = c.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String resolvePlayerName(ServerLevel level, UUID uuid) {
        if (level == null || uuid == null) return (uuid == null ? null : uuid.toString());
        try {
            var srv = level.getServer();
            if (srv != null) {
                var sp = srv.getPlayerList().getPlayer(uuid);
                if (sp != null) return sp.getGameProfile().getName();

                var cache = srv.getProfileCache();
                if (cache != null) {
                    Object opt = cache.getClass().getMethod("get", UUID.class).invoke(cache, uuid);
                    if (opt != null && (boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                        Object gp = opt.getClass().getMethod("get").invoke(opt);
                        String n = (String) gp.getClass().getMethod("getName").invoke(gp);
                        if (n != null && !n.isBlank()) return n;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return uuid.toString();
    }

    private static String safeUuid(UUID u) {
        return u == null ? "" : u.toString();
    }

    private static String safeStr(String s) {
        return s == null ? "" : s;
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}