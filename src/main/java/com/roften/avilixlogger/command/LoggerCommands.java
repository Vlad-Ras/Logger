package com.roften.avilixlogger.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.integrations.WorldEditIntegration;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import net.minecraft.world.level.storage.LevelResource;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import java.util.EnumSet;
import java.util.UUID;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletableFuture;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

public final class LoggerCommands {

    private LoggerCommands() {}

    public static void register(CommandDispatcher<CommandSourceStack> d) {
    d.register(literal("log")
            .requires(LoggerCommands::hasAnyLoggerPermission)
            .executes(LoggerCommands::lookupDefaultRoot)

            // Short help + examples
            .then(literal("help").executes(LoggerCommands::help))

            // Optional GUI (requires the client to have the mod installed)
            .then(literal("gui")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.gui", 2))
                    .executes(LoggerCommands::openGui))

            // Inspect tool (kept as requested): /log i
            .then(literal("i")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.inspect", 2))
                    .executes(LoggerCommands::inspectBlock)
                    .then(argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> inspectWithFlags(ctx, StringArgumentType.getString(ctx, "args")))))

            // Admin: configure inspect tool item
            .then(literal("tool")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.admin", 2))
                    .then(literal("reset").executes(LoggerCommands::resetInspectTool))
                    .then(argument("item", StringArgumentType.word())
                            .executes(ctx -> setInspectTool(ctx, StringArgumentType.getString(ctx, "item")))))

            // Plane ownership tools (Immersive Aircraft / Man of Many Planes)
            .then(literal("plane")
                    .requires(LoggerCommands::hasAnyPlanePermission)
                    .then(literal("find")
                            .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.find", 2))
                            .then(argument("player", StringArgumentType.word())
                                    .suggests((ctx, b) -> CompletableFuture.completedFuture(suggestOnlinePlayersSync(ctx, b)))
                                    .executes(ctx -> planeFindByOwner(ctx, StringArgumentType.getString(ctx, "player")))
                                    .then(literal("offline")
                                            .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.find.offline", 2))
                                            .executes(ctx -> planeFindByOwnerOffline(ctx, StringArgumentType.getString(ctx, "player"))))))

                    .then(literal("owner")
                            .then(literal("info")
                                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.info", 2))
                                    .executes(LoggerCommands::planeOwnerInfo))
                            .then(literal("set")
                                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.set", 2))
                                    .then(argument("player", StringArgumentType.word())
                                            .suggests((ctx, b) -> CompletableFuture.completedFuture(suggestOnlinePlayersSync(ctx, b)))
                                            .executes(ctx -> planeOwnerSet(ctx, StringArgumentType.getString(ctx, "player")))))
                            .then(literal("reset")
                                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.reset", 2))
                                    .executes(LoggerCommands::planeOwnerReset))))

            // Pagination
            .then(literal("page")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.page", 2))
                    .then(argument("number", IntegerArgumentType.integer(1, 100000))
                            .executes(ctx -> pageTo(ctx, IntegerArgumentType.getInteger(ctx, "number"))))
                    .then(literal("next").executes(ctx -> pageNav(ctx, PageNav.NEXT)))
                    .then(literal("prev").executes(ctx -> pageNav(ctx, PageNav.PREV)))
                    .then(literal("first").executes(ctx -> pageNav(ctx, PageNav.FIRST))))

            // Repeat last query: /log last [flags...]
            .then(literal("last")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.lookup", 2))
                    .executes(LoggerCommands::last)
                    .then(argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> lastWithFlags(ctx, StringArgumentType.getString(ctx, "args")))))

            // Presets (management)
            .then(literal("preset")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.admin", 2))
                    .then(literal("save")
                            .then(argument("name", StringArgumentType.word())
                                    .then(argument("args", StringArgumentType.greedyString())
                                            .executes(ctx -> presetSave(ctx,
                                                    StringArgumentType.getString(ctx, "name"),
                                                    StringArgumentType.getString(ctx, "args"))))))
                    .then(literal("run")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> presetRun(ctx, StringArgumentType.getString(ctx, "name"), ""))
                                    .then(argument("args", StringArgumentType.greedyString())
                                            .executes(ctx -> presetRun(ctx,
                                                    StringArgumentType.getString(ctx, "name"),
                                                    StringArgumentType.getString(ctx, "args"))))))
                    .then(literal("list").executes(LoggerCommands::presetList))
                    .then(literal("delete")
                            .then(argument("name", StringArgumentType.word())
                                    .executes(ctx -> presetDelete(ctx, StringArgumentType.getString(ctx, "name"))))))

            // Rollback (safe by default: preview; apply only with --confirm)
            .then(literal("rollback")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.admin", 2))
                    .then(argument("args", StringArgumentType.greedyString())
                            .executes(ctx -> rollbackFromFlags(ctx, StringArgumentType.getString(ctx, "args"))))
                    .executes(ctx -> rollbackFromFlags(ctx, "")))

            // Flag-based lookup: /log --time 30m --radius 10 --mode theft ...
            .then(argument("args", StringArgumentType.greedyString())
                    .requires(LoggerCommands::hasLookupPermission)
                    .executes(ctx -> lookupFromFlags(ctx, StringArgumentType.getString(ctx, "args"))))
    );

    // Aliases for plane ownership tools:
    // /owner info|set|reset  ->  /log plane owner info|set|reset
    d.register(literal("owner")
            .requires(LoggerCommands::hasAnyPlaneOwnerPermission)
            .then(literal("info")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.info", 2))
                    .executes(LoggerCommands::planeOwnerInfo))
            .then(literal("set")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.set", 2))
                    .then(argument("player", StringArgumentType.word())
                            .suggests((ctx, b) -> CompletableFuture.completedFuture(suggestOnlinePlayersSync(ctx, b)))
                            .executes(ctx -> planeOwnerSet(ctx, StringArgumentType.getString(ctx, "player")))))
            .then(literal("reset")
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane.owner.reset", 2))
                    .executes(LoggerCommands::planeOwnerReset))
    );
}


    private static boolean hasAnyLoggerPermission(CommandSourceStack source) {
        return com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(source.getServer())
                && com.roften.avilixlogger.core.PermissionUtil.hasAny(source, 2,
                "avilixlogger.command.use",
                "avilixlogger.command.lookup",
                "avilixlogger.command.inspect",
                "avilixlogger.command.page",
                "avilixlogger.command.admin",
                "avilixlogger.command.plane",
                "avilixlogger.command.plane.find",
                "avilixlogger.command.plane.find.offline",
                "avilixlogger.command.plane.owner.info",
                "avilixlogger.command.plane.owner.set",
                "avilixlogger.command.plane.owner.reset",
                "avilixlogger.gui"
        );
    }

    private static boolean hasLookupPermission(CommandSourceStack source) {
        return com.roften.avilixlogger.core.PermissionUtil.hasAny(source, 2,
                "avilixlogger.command.use",
                "avilixlogger.command.lookup"
        );
    }

    private static boolean hasAnyPlanePermission(CommandSourceStack source) {
        return com.roften.avilixlogger.core.PermissionUtil.hasAny(source, 2,
                "avilixlogger.command.plane",
                "avilixlogger.command.plane.find",
                "avilixlogger.command.plane.find.offline",
                "avilixlogger.command.plane.owner.info",
                "avilixlogger.command.plane.owner.set",
                "avilixlogger.command.plane.owner.reset"
        );
    }

    private static boolean hasAnyPlaneOwnerPermission(CommandSourceStack source) {
        return com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized(source.getServer())
                && com.roften.avilixlogger.core.PermissionUtil.hasAny(source, 2,
                "avilixlogger.command.plane",
                "avilixlogger.command.plane.owner.info",
                "avilixlogger.command.plane.owner.set",
                "avilixlogger.command.plane.owner.reset"
        );
    }

    private static int parseDurationSeconds(String input) {
        if (input == null) return 0;
        String s = input.trim().toLowerCase();
        if (s.isEmpty()) return 0;

        // Backward-compatible: plain number means seconds
        boolean allDigits = true;
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) { allDigits = false; break; }
        }
        if (allDigits) {
            try { return Integer.parseInt(s); } catch (Exception ignored) { return 0; }
        }

        char last = s.charAt(s.length() - 1);
        String numPart = s.substring(0, s.length() - 1);
        if (numPart.isEmpty()) return 0;
        for (int i = 0; i < numPart.length(); i++) {
            if (!Character.isDigit(numPart.charAt(i))) return 0;
        }

        long mul = switch (last) {
            case 's' -> 1L;
            case 'm' -> 60L;
            case 'h' -> 60L * 60L;
            case 'd' -> 60L * 60L * 24L;
            default -> -1L;
        };
        if (mul <= 0) return 0;

        try {
            long v = Long.parseLong(numPart);
            long sec = v * mul;
            if (sec > Integer.MAX_VALUE) return Integer.MAX_VALUE;
            return (int) sec;
        } catch (Exception ignored) {
            return 0;
        }
    }

    /**
     * Parses a calendar day into [startMs, endMs) in the server's timezone.
     * Supported formats:
     * - yyyy-MM-dd (2026-01-25)
     * - dd.MM.yyyy (25.01.2026)
     * - dd/MM/yyyy (25/01/2026)
     */
    private static long[] parseDayRangeMillis(String dateStr) {
        if (dateStr == null) return null;
        String s = dateStr.trim();
        if (s.isEmpty()) return null;
        LocalDate d;
        try {
            if (s.indexOf('-') > 0) {
                d = LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE);
            } else {
                DateTimeFormatter f = s.contains("/")
                        ? DateTimeFormatter.ofPattern("dd/MM/yyyy")
                        : DateTimeFormatter.ofPattern("dd.MM.yyyy");
                d = LocalDate.parse(s, f);
            }
        } catch (DateTimeParseException ex) {
            return null;
        }
        ZoneId z = ZoneId.systemDefault();
        long start = d.atStartOfDay(z).toInstant().toEpochMilli();
        long end = d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli();
        return new long[]{start, end};
    }

    /** Parses an absolute rollback target moment in the server timezone. */
    private static Long parseTargetMomentMillis(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().replace('_', 'T').replace('@', 'T');
        ZoneId zone = ZoneId.systemDefault();
        try {
            if (!normalized.contains("T")) {
                return LocalDate.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE)
                        .atStartOfDay(zone).toInstant().toEpochMilli();
            }
            return java.time.LocalDateTime.parse(normalized, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                    .atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static String formatDuration(int seconds) {
        int s = Math.max(0, seconds);
        if (s < 60) return s + "s";
        if (s < 3600) {
            int m = s / 60;
            int rem = s % 60;
            return rem == 0 ? (m + "m") : (m + "m " + rem + "s");
        }
        if (s < 86400) {
            int h = s / 3600;
            int rem = s % 3600;
            int m = rem / 60;
            return m == 0 ? (h + "h") : (h + "h " + m + "m");
        }
        int d = s / 86400;
        int rem = s % 86400;
        int h = rem / 3600;
        return h == 0 ? (d + "d") : (d + "d " + h + "h");
    }

    // --- Plane owner commands (Immersive Aircraft / Man of Many Planes) ---

    /**
     * Synchronous suggestions builder (wrapped into a CompletableFuture at call-sites).
     * This avoids Java generic inference/return-type issues across different toolchains.
     */
    private static Suggestions suggestOnlinePlayersSync(CommandContext<CommandSourceStack> ctx, SuggestionsBuilder b) {
        try {
            var src = ctx.getSource();
            if (src.getServer() == null) return b.build();

            for (var p : src.getServer().getPlayerList().getPlayers()) {
                b.suggest(p.getGameProfile().getName());
            }
            return b.build();
        } catch (Throwable t) {
            return b.build();
        }
    }

    private static ItemStack getPlaneItem(ServerPlayer player) {
        if (player == null) return ItemStack.EMPTY;
        ItemStack main = player.getMainHandItem();
        if (AirplanesCompatHooks.isPlaneItem(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (AirplanesCompatHooks.isPlaneItem(off)) return off;
        return ItemStack.EMPTY;
    }

    private static int planeOwnerInfo(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Команда доступна только игроку."));
            return 0;
        }
        ItemStack plane = getPlaneItem(sp);
        if (plane.isEmpty()) {
            sp.sendSystemMessage(Component.literal("Возьми самолёт (VehicleItem) в руку.").withStyle(ChatFormatting.RED));
            return 0;
        }

        var tag = AirplanesCompatHooks.getOwnerTag(plane);
        if (tag == null) {
            sp.sendSystemMessage(Component.literal("У этого самолёта владелец не задан.").withStyle(ChatFormatting.YELLOW));
            return 1;
        }

        String name = tag.contains(AirplanesCompatHooks.OWNER_NAME_KEY) ? tag.getString(AirplanesCompatHooks.OWNER_NAME_KEY) : null;
        String uuid = tag.contains(AirplanesCompatHooks.OWNER_UUID_KEY) ? tag.getString(AirplanesCompatHooks.OWNER_UUID_KEY) : (tag.contains(AirplanesCompatHooks.OWNER_KEY) ? tag.getString(AirplanesCompatHooks.OWNER_KEY) : null);

        MutableComponent msg = Component.literal("Владелец: ").withStyle(ChatFormatting.GRAY);
        if (name != null && !name.isBlank()) {
            msg.append(Component.literal(name).withStyle(ChatFormatting.AQUA));
        } else {
            msg.append(Component.literal("?").withStyle(ChatFormatting.AQUA));
        }
        if (uuid != null && !uuid.isBlank()) {
            msg.append(Component.literal(" (" + uuid + ")").withStyle(ChatFormatting.DARK_GRAY));
        }
        sp.sendSystemMessage(msg);
        return 1;
    }

    private static int planeOwnerReset(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Команда доступна только игроку."));
            return 0;
        }
        ItemStack plane = getPlaneItem(sp);
        if (plane.isEmpty()) {
            sp.sendSystemMessage(Component.literal("Возьми самолёт (VehicleItem) в руку.").withStyle(ChatFormatting.RED));
            return 0;
        }

        var old = AirplanesCompatHooks.getOwnerTag(plane);
        String oldName = old != null && old.contains(AirplanesCompatHooks.OWNER_NAME_KEY)
                ? old.getString(AirplanesCompatHooks.OWNER_NAME_KEY)
                : null;
        String oldUuid = old != null && (old.contains(AirplanesCompatHooks.OWNER_UUID_KEY) || old.contains(AirplanesCompatHooks.OWNER_KEY))
                ? (old.contains(AirplanesCompatHooks.OWNER_UUID_KEY)
                    ? old.getString(AirplanesCompatHooks.OWNER_UUID_KEY)
                    : old.getString(AirplanesCompatHooks.OWNER_KEY))
                : null;

        AirplanesCompatHooks.clearOwnerOnItem(plane);

        logPlaneOwnerChange(sp, plane, oldName, oldUuid, null, null);
        sp.sendSystemMessage(Component.literal("Владелец сброшен.").withStyle(ChatFormatting.YELLOW));
        return 1;
    }

    private static int planeOwnerSet(CommandContext<CommandSourceStack> ctx, String playerName) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Команда доступна только игроку."));
            return 0;
        }
        ItemStack plane = getPlaneItem(sp);
        if (plane.isEmpty()) {
            sp.sendSystemMessage(Component.literal("Возьми самолёт (VehicleItem) в руку.").withStyle(ChatFormatting.RED));
            return 0;
        }

        // old
        var old = AirplanesCompatHooks.getOwnerTag(plane);
        String oldName = old != null && old.contains(AirplanesCompatHooks.OWNER_NAME_KEY) ? old.getString(AirplanesCompatHooks.OWNER_NAME_KEY) : null;
        String oldUuid = old != null && (old.contains(AirplanesCompatHooks.OWNER_UUID_KEY) || old.contains(AirplanesCompatHooks.OWNER_KEY))
                ? (old.contains(AirplanesCompatHooks.OWNER_UUID_KEY) ? old.getString(AirplanesCompatHooks.OWNER_UUID_KEY) : old.getString(AirplanesCompatHooks.OWNER_KEY))
                : null;

        String newName = playerName;
        String newUuid = null;

        // resolve online first, then profile cache (offline players)
        try {
            var server = sp.getServer();
            ServerPlayer target = server.getPlayerList().getPlayerByName(playerName);
            if (target != null) {
                newName = target.getGameProfile().getName();
                newUuid = target.getUUID().toString();
            } else {
                // Best-effort offline resolve (uses server profile cache).
                try {
                    Object cache = server.getProfileCache();
                    if (cache != null) {
                        Object opt = cache.getClass().getMethod("get", String.class).invoke(cache, playerName);
                        // Optional<GameProfile> in vanilla
                        if (opt != null && (boolean) opt.getClass().getMethod("isPresent").invoke(opt)) {
                            Object gp = opt.getClass().getMethod("get").invoke(opt);
                            String n = (String) gp.getClass().getMethod("getName").invoke(gp);
                            Object id = gp.getClass().getMethod("getId").invoke(gp);
                            if (n != null && !n.isBlank()) newName = n;
                            if (id != null) newUuid = id.toString();
                        }
                    }
                } catch (Throwable ignored2) {}
            }
        } catch (Throwable ignored) {}

        AirplanesCompatHooks.setOwnerOnItem(plane, newName, newUuid);
        logPlaneOwnerChange(sp, plane, oldName, oldUuid, newName, newUuid);

        sp.sendSystemMessage(Component.literal("Владелец установлен: ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(newName).withStyle(ChatFormatting.AQUA))
                .append(newUuid != null ? Component.literal(" (" + newUuid + ")").withStyle(ChatFormatting.DARK_GRAY) : Component.empty()));
        return 1;
    }

    private static void logPlaneOwnerChange(ServerPlayer actor, ItemStack plane, String oldName, String oldUuid, String newName, String newUuid) {
        try {
            ServerLevel level = actor.serverLevel();
            if (!LoggerConfig.isEnabled() || !LoggerConfig.VALUES.logEntities.get()) return;

            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = level.dimension().location().toString();
            e.type = ActionType.ENTITY_OWNER_SET;
            e.actorUuid = actor.getUUID();
            e.actorName = actor.getName().getString();

            var pos = actor.blockPosition();
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();

            e.itemStackNbt = NbtSerde.writeItemStack(plane, level.registryAccess());
            e.count = plane.getCount();

            String planeName = null;
            String planeId = null;
            String customName = null;
            try {
                planeName = plane.getHoverName() != null ? plane.getHoverName().getString() : null;
                if (plane.has(DataComponents.CUSTOM_NAME)) customName = plane.getHoverName().getString();
            } catch (Throwable ignored2) {}
            try {
                planeId = net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(plane.getItem()).toString();
            } catch (Throwable ignored2) {}

            e.extra = "{\"kind\":\"plane_owner\",\"planeId\":" + jsonStr(planeId) + ",\"planeName\":" + jsonStr(planeName)
                    + ",\"customName\":" + jsonStr(customName)
                    + ",\"old_name\":" + jsonStr(oldName) + ",\"old_uuid\":" + jsonStr(oldUuid)
                    + ",\"new_name\":" + jsonStr(newName) + ",\"new_uuid\":" + jsonStr(newUuid) + "}";

            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {}
    }

    private static String jsonStr(String s) {
        if (s == null) return "null";
        String esc = s.replace("\\", "\\\\").replace("\"", "\\\"");
        return "\"" + esc + "\"";
    }


    private record FoundPlane(UUID entityUuid, String name, String dim, BlockPos pos, double dist2) {}

    private static int planeFindByOwner(CommandContext<CommandSourceStack> ctx, String playerName) {
        CommandSourceStack src = ctx.getSource();
        var server = src.getServer();

        String targetName = playerName;
        String targetUuid = null;

        // Resolve online player (preferred: stable UUID)
        try {
            ServerPlayer t = server.getPlayerList().getPlayerByName(playerName);
            if (t != null) {
                targetName = t.getGameProfile().getName();
                targetUuid = t.getUUID().toString();
            }
        } catch (Throwable ignored) {}

        final double sx = src.getPosition().x;
        final double sy = src.getPosition().y;
        final double sz = src.getPosition().z;

        List<FoundPlane> found = new ArrayList<>();
        for (ServerLevel lvl : server.getAllLevels()) {
            // This returns only LOADED entities; AABB is used as a "match all" area.
            for (var e : lvl.getEntities(null, new AABB(-30_000_000, -2048, -30_000_000, 30_000_000, 4096, 30_000_000))) {
                if (!AirplanesCompatHooks.isPlaneEntity(e)) continue;

                var tag = AirplanesCompatHooks.getOwnerTag(e);
                if (tag == null) continue;

                String ou = AirplanesCompatHooks.ownerUuidFromTag(tag);
                String on = AirplanesCompatHooks.ownerNameFromTag(tag);

                boolean match = false;
                if (targetUuid != null && ou != null && ou.equalsIgnoreCase(targetUuid)) {
                    match = true;
                } else if (on != null && on.equalsIgnoreCase(targetName)) {
                    match = true;
                } else if (targetUuid == null && ou != null && ou.equalsIgnoreCase(playerName)) {
                    // Allow searching by raw UUID string
                    match = true;
                }

                if (!match) continue;

                BlockPos p = e.blockPosition();
                double dx = (p.getX() + 0.5) - sx;
                double dy = (p.getY() + 0.5) - sy;
                double dz = (p.getZ() + 0.5) - sz;
                double d2 = dx * dx + dy * dy + dz * dz;

                found.add(new FoundPlane(e.getUUID(), e.getName().getString(), lvl.dimension().location().toString(), p, d2));
            }
        }

        found.sort(Comparator.comparingDouble(FoundPlane::dist2));

        String who = (targetUuid != null)
                ? (targetName + " (" + targetUuid + ")")
                : targetName;

        if (found.isEmpty()) {
            src.sendFailure(Component.literal("Самолёты владельца не найдены среди загруженных сущностей: ")
                    .withStyle(ChatFormatting.RED)
                    .append(Component.literal(who).withStyle(ChatFormatting.AQUA)));
            src.sendSystemMessage(Component.literal("Важно: поиск работает только по ЗАГРУЖЕННЫМ самолётам (в чанках, которые сейчас активны).")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return 0;
        }

        src.sendSystemMessage(Component.literal("Найдено самолётов: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(found.size())).withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" | владелец: ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(who).withStyle(ChatFormatting.AQUA)));

        int limit = Math.min(20, found.size());
        for (int i = 0; i < limit; i++) {
            FoundPlane fp = found.get(i);
            BlockPos p = fp.pos();

            String tpCmd = "/execute in " + fp.dim() + " run tp @s " + p.getX() + " " + p.getY() + " " + p.getZ();

            MutableComponent line = Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                    .append(Component.literal(fp.name()).withStyle(ChatFormatting.YELLOW))
                    .append(Component.literal(" @ ").withStyle(ChatFormatting.GRAY))
                    .append(Component.literal(p.getX() + " " + p.getY() + " " + p.getZ()).withStyle(ChatFormatting.WHITE))
                    .append(Component.literal(" (" + fp.dim() + ")").withStyle(ChatFormatting.DARK_AQUA));

            line.withStyle(style -> style
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                            Component.literal("UUID: ").withStyle(ChatFormatting.GRAY)
                                    .append(Component.literal(fp.entityUuid().toString()).withStyle(ChatFormatting.AQUA))
                                    .append(Component.literal("\nКлик: телепорт").withStyle(ChatFormatting.GRAY))))
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, tpCmd)));

            src.sendSystemMessage(line);
        }

        if (found.size() > 20) {
            src.sendSystemMessage(Component.literal("Показаны первые 20. Всего: " + found.size()).withStyle(ChatFormatting.DARK_GRAY));
        }

        return 1;
    }

    /**
     * Offline scan: searches planes in entity storage region files (unloaded chunks too).
     * This runs asynchronously to avoid TPS spikes.
     */
    private static int planeFindByOwnerOffline(CommandContext<CommandSourceStack> ctx, String playerName) {
        CommandSourceStack src = ctx.getSource();
        if (!(src.getEntity() instanceof ServerPlayer sp)) {
            src.sendFailure(Component.literal("Команда доступна только игроку.").withStyle(ChatFormatting.RED));
            return 0;
        }
        MinecraftServer server = src.getServer();
        if (server == null) return 0;

        // Resolve online player (preferred: stable UUID)
        String targetName = playerName;
        String targetUuid = null;
        try {
            ServerPlayer t = server.getPlayerList().getPlayerByName(playerName);
            if (t != null) {
                targetName = t.getGameProfile().getName();
                targetUuid = t.getUUID().toString();
            }
        } catch (Throwable ignored) {}

        // Allow searching by raw UUID string
        if (targetUuid == null) {
            try {
                UUID.fromString(playerName);
                targetUuid = playerName;
            } catch (Throwable ignored) {}
        }

        final String who = (targetUuid != null) ? (targetName + " (" + targetUuid + ")") : targetName;
        final double sx = src.getPosition().x;
        final double sy = src.getPosition().y;
        final double sz = src.getPosition().z;

        // World root (where DIM folders live)
        final Path root = server.getWorldPath(LevelResource.ROOT);

        // Capture for async thread (make effectively-final for lambda)
        final String targetNameFinal = targetName;
        final String targetUuidFinal = targetUuid;

        src.sendSystemMessage(Component.literal("Офлайн-поиск самолётов запущен (скан сущностей мира). Владелец: ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(who).withStyle(ChatFormatting.AQUA)));
        src.sendSystemMessage(Component.literal("Важно: это может занять время на больших мирах. Поиск выполняется в фоне, TPS не должен просесть.")
                .withStyle(ChatFormatting.DARK_GRAY));

        Thread scanThread = new Thread(() -> {
            try {
                ArrayList<FoundPlane> found = new ArrayList<>();
                int filesScanned = 0;

                for (ServerLevel lvl : server.getAllLevels()) {
                    String dimId = lvl.dimension().location().toString();
                    Path dimFolder = resolveDimensionFolder(root, dimId);
                    Path entitiesDir = dimFolder.resolve("entities");
                    if (!Files.isDirectory(entitiesDir)) continue;

                    try (Stream<Path> s = Files.list(entitiesDir)) {
                        for (Path p : (Iterable<Path>) s.filter(f -> f.getFileName().toString().endsWith(".mca"))::iterator) {
                            filesScanned++;
                            scanEntityRegionFile(p, dimId, targetNameFinal, targetUuidFinal, sx, sy, sz, found);
                        }
                    } catch (Throwable ignored) {}
                }

                found.sort(Comparator.comparingDouble(FoundPlane::dist2));

                final int total = found.size();
                final int scanned = filesScanned;

                server.execute(() -> {
                    if (!sp.isAlive() || sp.connection == null) return;

                    if (total == 0) {
                        sp.sendSystemMessage(Component.literal("Офлайн-поиск завершён: самолёты владельца не найдены. (регионов просмотрено: " + scanned + ")")
                                .withStyle(ChatFormatting.RED));
                        sp.sendSystemMessage(Component.literal("Подсказка: если мод самолётов хранит owner не в сущности/Entities, а в Tile/Capabilities, тогда нужно будет другой парсер.")
                                .withStyle(ChatFormatting.DARK_GRAY));
                        return;
                    }

                    sp.sendSystemMessage(Component.literal("Офлайн-поиск завершён. Найдено самолётов: ")
                            .withStyle(ChatFormatting.GRAY)
                            .append(Component.literal(String.valueOf(total)).withStyle(ChatFormatting.GREEN))
                            .append(Component.literal(" | владелец: ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(who).withStyle(ChatFormatting.AQUA))
                            .append(Component.literal(" | регионов: " + scanned).withStyle(ChatFormatting.DARK_GRAY)));

                    int limit = Math.min(30, total);
                    for (int i = 0; i < limit; i++) {
                        FoundPlane fp = found.get(i);
                        BlockPos pos = fp.pos();
                        String tpCmd = "/execute in " + fp.dim() + " run tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ();

                        MutableComponent line = Component.literal("• ").withStyle(ChatFormatting.DARK_GRAY)
                                .append(Component.literal(fp.name()).withStyle(ChatFormatting.YELLOW))
                                .append(Component.literal(" @ ").withStyle(ChatFormatting.GRAY))
                                .append(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.WHITE))
                                .append(Component.literal(" (" + fp.dim() + ")").withStyle(ChatFormatting.DARK_AQUA));

                        line.withStyle(style -> style
                                .withHoverEvent(new net.minecraft.network.chat.HoverEvent(net.minecraft.network.chat.HoverEvent.Action.SHOW_TEXT,
                                        Component.literal("UUID: ").withStyle(ChatFormatting.GRAY)
                                                .append(Component.literal(fp.entityUuid().toString()).withStyle(ChatFormatting.AQUA))
                                                .append(Component.literal("\nКлик: телепорт").withStyle(ChatFormatting.GRAY))))
                                .withClickEvent(new net.minecraft.network.chat.ClickEvent(net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, tpCmd)));

                        sp.sendSystemMessage(line);
                    }

                    if (total > 30) {
                        sp.sendSystemMessage(Component.literal("Показаны первые 30. Всего: " + total).withStyle(ChatFormatting.DARK_GRAY));
                    }
                });

            } catch (Throwable t) {
                server.execute(() -> sp.sendSystemMessage(Component.literal("Офлайн-поиск упал с ошибкой: " + t.getClass().getSimpleName())
                        .withStyle(ChatFormatting.RED)));
            }
        }, "avilixlogger-plane-offline-scan");
        scanThread.setDaemon(true);
        scanThread.start();

        return 1;
    }

    /** Resolves a dimension folder path from world root and dimension id string. */
    private static Path resolveDimensionFolder(Path worldRoot, String dimId) {
        // Vanilla ids
        if ("minecraft:overworld".equals(dimId)) return worldRoot;
        if ("minecraft:the_nether".equals(dimId)) return worldRoot.resolve("DIM-1");
        if ("minecraft:the_end".equals(dimId)) return worldRoot.resolve("DIM1");

        // Modded dimension: worldRoot/dimensions/<namespace>/<path>
        try {
            int i = dimId.indexOf(':');
            if (i > 0) {
                String ns = dimId.substring(0, i);
                String path = dimId.substring(i + 1);
                return worldRoot.resolve("dimensions").resolve(ns).resolve(path);
            }
        } catch (Throwable ignored) {}
        return worldRoot;
    }

    /** Scans a single entities region (.mca) file and appends matches into found list. */
    private static void scanEntityRegionFile(Path regionPath, String dimId, String targetName, String targetUuid,
                                             double sx, double sy, double sz, List<FoundPlane> found) {
        if (regionPath == null) return;

        // We use reflection to stay resilient across mapping/signature changes.
        Object regionFile = null;
        try {
            Class<?> rfCls = Class.forName("net.minecraft.world.level.chunk.storage.RegionFile");

            // Try constructors:
            // (Path, Path, boolean)
            // (Path, Path, boolean, int)
            // (Path, Path, boolean, ...)
            Path parent = regionPath.getParent();

            java.lang.reflect.Constructor<?> best = null;
            for (var c : rfCls.getConstructors()) {
                Class<?>[] pt = c.getParameterTypes();
                if (pt.length >= 3 && Path.class.isAssignableFrom(pt[0]) && Path.class.isAssignableFrom(pt[1]) && pt[2] == boolean.class) {
                    best = c;
                    break;
                }
            }

            if (best == null) return;

            Object[] args;
            if (best.getParameterCount() == 3) {
                args = new Object[]{regionPath, parent, true};
            } else {
                args = new Object[best.getParameterCount()];
                args[0] = regionPath;
                args[1] = parent;
                args[2] = true;
                // Fill the rest with zeros/nulls (safe defaults).
                for (int i = 3; i < args.length; i++) {
                    Class<?> t = best.getParameterTypes()[i];
                    if (t == int.class) args[i] = 0;
                    else if (t == boolean.class) args[i] = false;
                    else args[i] = null;
                }
            }

            regionFile = best.newInstance(args);

            java.lang.reflect.Method getStream = null;
            for (String m : new String[]{"getChunkDataInputStream", "getChunkDataInputStream"}) {
                try {
                    getStream = rfCls.getMethod(m, net.minecraft.world.level.ChunkPos.class);
                    break;
                } catch (Throwable ignored) {}
            }
            if (getStream == null) return;

            // Iterate 32x32 chunk entries in this region.
            for (int cx = 0; cx < 32; cx++) {
                for (int cz = 0; cz < 32; cz++) {
                    net.minecraft.world.level.ChunkPos cp = new net.minecraft.world.level.ChunkPos(
                            regionCoord(regionPath.getFileName().toString(), 1) * 32 + cx,
                            regionCoord(regionPath.getFileName().toString(), 2) * 32 + cz
                    );
                    DataInputStream dis = null;
                    try {
                        Object o = getStream.invoke(regionFile, cp);
                        if (!(o instanceof DataInputStream d)) continue;
                        dis = d;
                        CompoundTag root = NbtIo.read(dis);
                        if (root == null) continue;

                        // Entity storage: list is typically under "Entities".
                        ListTag ents = null;
                        if (root.contains("Entities", Tag.TAG_LIST)) {
                            ents = root.getList("Entities", Tag.TAG_COMPOUND);
                        } else if (root.contains("entities", Tag.TAG_LIST)) {
                            ents = root.getList("entities", Tag.TAG_COMPOUND);
                        }
                        if (ents == null || ents.isEmpty()) continue;

                        for (int i = 0; i < ents.size(); i++) {
                            CompoundTag et = ents.getCompound(i);

                            // Quick plane heuristic: id namespace or owner keys.
                            String id = et.contains("id") ? et.getString("id") : "";
                            boolean looksLikePlane = (id.contains("immersive_aircraft") || id.contains("aircraft") || id.contains("plane"));

                            String ou = null;
                            String on = null;
                            if (et.contains(AirplanesCompatHooks.OWNER_UUID_KEY)) ou = et.getString(AirplanesCompatHooks.OWNER_UUID_KEY);
                            if (ou == null || ou.isBlank()) {
                                if (et.contains(AirplanesCompatHooks.OWNER_KEY)) ou = et.getString(AirplanesCompatHooks.OWNER_KEY);
                            }
                            if (et.contains(AirplanesCompatHooks.OWNER_NAME_KEY)) on = et.getString(AirplanesCompatHooks.OWNER_NAME_KEY);

                            if (ou == null && on == null && !looksLikePlane) continue;

                            boolean match = false;
                            if (targetUuid != null && ou != null && ou.equalsIgnoreCase(targetUuid)) match = true;
                            else if (on != null && on.equalsIgnoreCase(targetName)) match = true;
                            else if (targetUuid == null && ou != null && ou.equalsIgnoreCase(targetName)) match = true;
                            if (!match) continue;

                            // Extract position.
                            BlockPos pos = null;
                            try {
                                if (et.contains("Pos", Tag.TAG_LIST)) {
                                    ListTag p = et.getList("Pos", Tag.TAG_DOUBLE);
                                    if (p.size() >= 3) {
                                        pos = BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2));
                                    }
                                } else if (et.contains("pos", Tag.TAG_LIST)) {
                                    ListTag p = et.getList("pos", Tag.TAG_DOUBLE);
                                    if (p.size() >= 3) {
                                        pos = BlockPos.containing(p.getDouble(0), p.getDouble(1), p.getDouble(2));
                                    }
                                }
                            } catch (Throwable ignored) {}
                            if (pos == null) {
                                // Fallback to chunk center
                                pos = new BlockPos(cp.getMinBlockX() + 8, 64, cp.getMinBlockZ() + 8);
                            }

                            UUID uuid = null;
                            try {
                                uuid = NbtUtils.loadUUID(et);
                            } catch (Throwable ignored) {}
                            if (uuid == null) uuid = UUID.randomUUID();

                            double dx = (pos.getX() + 0.5) - sx;
                            double dy = (pos.getY() + 0.5) - sy;
                            double dz = (pos.getZ() + 0.5) - sz;
                            double d2 = dx * dx + dy * dy + dz * dz;

                            String name = !id.isBlank() ? id : "plane";
                            found.add(new FoundPlane(uuid, name, dimId, pos, d2));
                        }
                    } catch (Throwable ignored) {
                        // ignore broken entries
                    } finally {
                        try { if (dis != null) dis.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try {
                if (regionFile != null) {
                    regionFile.getClass().getMethod("close").invoke(regionFile);
                }
            } catch (Throwable ignored) {}
        }
    }

    /** Parses region filename r.<x>.<z>.mca -> returns x or z depending on idx (1=x,2=z). */
    private static int regionCoord(String fileName, int idx) {
        try {
            // r.0.0.mca
            String[] parts = fileName.split("\\.");
            if (parts.length >= 4) {
                return Integer.parseInt(parts[idx]);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static int pageTo(CommandContext<CommandSourceStack> ctx, int targetPage) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Пагинация доступна только игроку."));
            return 0;
        }
        LastQueryManager.State st = LastQueryManager.get(sp);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Нет активного запроса логов. Сначала выполните /log i или /log --time 30m."));
            return 0;
        }
        ServerLevel level = resolveLevelForDim(ctx.getSource(), st.baseQuery.dim);
        if (level == null) level = sp.serverLevel();
        int target = Math.max(1, targetPage);

        int current = st.pageIndex();
        if (target == 1) {
            LastQueryManager.first(sp);
        } else if (target < current) {
            while (st != null && st.pageIndex() > target) {
                LastQueryManager.prev(sp);
                st = LastQueryManager.get(sp);
            }
        } else if (target == current + 1 && st.hasNext() && st.nextCursorCandidate() > 0L) {
            LastQueryManager.next(sp, st.nextCursorCandidate());
        } else if (target != current) {
            ctx.getSource().sendFailure(Component.literal("Чтобы не блокировать сервер серией запросов, переходи кнопкой «Вперёд» последовательно."));
            return 0;
        }

        st = LastQueryManager.get(sp);
        if (st != null) {
            ServerLevel lvl = resolveLevelForDim(ctx.getSource(), st.baseQuery.dim);
            if (lvl == null) lvl = level;
            ChatLogPager.renderAndSend(lvl, sp, st);
        }
        return 1;
    }

    private enum PageNav { NEXT, PREV, FIRST }

    private static int inspectBlock(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        boolean enabled = InspectManager.toggle(sp);
        if (enabled) {
            ctx.getSource().sendSystemMessage(Component.literal("Режим осмотра включён. ПКМ по блокам предметом "
                    + LoggerConfig.inspectToolItemId() + " чтобы увидеть историю. Выполните /log i ещё раз, чтобы выключить."));
        } else {
            ctx.getSource().sendSystemMessage(Component.literal("Режим осмотра выключен."));
        }
        return 1;
    }

    private static int openGui(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }

        if (!com.roften.avilixlogger.net.LoggerNetwork.isClientPresent(sp)) {
            ctx.getSource().sendFailure(Component.literal("GUI доступен только если на клиенте установлен AvilixLogger. Без клиента используйте команды /log."));
            return 0;
        }

        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, new com.roften.avilixlogger.net.S2COpenGuiPayload());
        return 1;
    }

    private static void showBlockLogToChat(CommandSourceStack src, ServerLevel level, BlockPos pos) {
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.sinceTs = System.currentTimeMillis() - 7L * 24L * 60L * 60_000L; // last 7 days by default
        q.untilTs = System.currentTimeMillis();
        q.exactPos = pos;

        if (src.getEntity() instanceof ServerPlayer sp) {
            LastQueryManager.State st = LastQueryManager.set(sp, q, "Логи @ " + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            ChatLogPager.renderAndSend(level, sp, st);
            return;
        }

        // Console / command blocks: no interactive pagination.
        q.limit = Math.min(50, LoggerConfig.VALUES.lookupDefaultLimit.get() * 2);
        List<LogEntry> entries = LoggerRuntime.storage(level).queryReverse(q);
        src.sendSystemMessage(Component.literal("--- Логи для " + q.dim + " " + pos.getX() + "," + pos.getY() + "," + pos.getZ() + " ---"));
        if (entries.isEmpty()) {
            src.sendSystemMessage(Component.literal("(нет записей)"));
            return;
        }
        for (LogEntry e : entries) {
            if (e != null && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && e.extra != null && e.extra.startsWith("container change")) continue;
            src.sendSystemMessage(LogText.toChatLine(level, e));
        }
    }

    private static int lookup(CommandContext<CommandSourceStack> ctx, int seconds, int radius, String actor) {
        if (seconds <= 0) {
            ctx.getSource().sendFailure(Component.literal("Неверный интервал времени. Используйте число секунд или суффиксы s/m/h/d, например: 30s, 10m, 2h, 1d"));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        BlockPos center;
        if (ctx.getSource().getEntity() != null) {
            center = ctx.getSource().getEntity().blockPosition();
        } else {
            center = BlockPos.ZERO;
        }

        LogQuery q = new LogQuery();
        q.dim = radius <= 0 ? "*" : level.dimension().location().toString();
        q.sinceTs = System.currentTimeMillis() - (seconds * 1000L);
        q.untilTs = System.currentTimeMillis();
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;
        if (radius > 0) {
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
        } else {
            q.exactPos = null;
            q.minPos = null;
            q.maxPos = null;
        }

        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
            String title = "Поиск " + formatDuration(seconds) + ", r=" + radius;
            LastQueryManager.State st = LastQueryManager.set(sp, q, title);
            ChatLogPager.renderAndSend(level, sp, st);
            return 1;
        }

        // Console / command blocks: print without interactive pagination.
        q.limit = Math.min(200, LoggerConfig.VALUES.lookupDefaultLimit.get());
        List<LogEntry> entries = LoggerRuntime.storage(level).queryReverse(q);
        ctx.getSource().sendSystemMessage(Component.literal("--- Поиск (" + formatDuration(seconds) + ", r=" + radius + ") ---"));
        if (entries.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("(нет записей)"));
            return 1;
        }
        for (LogEntry e : entries) {
            if (e != null && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && e.extra != null && e.extra.startsWith("container change")) continue;
            ctx.getSource().sendSystemMessage(LogText.toChatLine(level, e));
        }
        return 1;
    }

    private static int pageNav(CommandContext<CommandSourceStack> ctx, PageNav nav) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Пагинация доступна только игроку."));
            return 0;
        }
        LastQueryManager.State st = LastQueryManager.get(sp);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Нет активного запроса логов. Сначала выполните /log i или /log --time 30m."));
            return 0;
        }

        ServerLevel level = resolveLevelForDim(ctx.getSource(), st.baseQuery.dim);
        if (level == null) level = sp.serverLevel();
        switch (nav) {
            case NEXT -> {
                if (st.hasNext() && st.nextCursorCandidate() > 0) {
                    LastQueryManager.next(sp, st.nextCursorCandidate());
                    st = LastQueryManager.get(sp);
                }
            }
            case PREV -> {
                if (st.hasPrev()) {
                    LastQueryManager.prev(sp);
                    st = LastQueryManager.get(sp);
                }
            }
            case FIRST -> {
                LastQueryManager.first(sp);
                st = LastQueryManager.get(sp);
            }
        }
        if (st != null) ChatLogPager.renderAndSend(level, sp, st);
        return 1;
    }


    private static ServerLevel resolveLevelForDim(CommandSourceStack src, String dimId) {
        if (src.getServer() == null || dimId == null || dimId.isBlank()) return null;
        try {
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, net.minecraft.resources.ResourceLocation.parse(dimId));
            return src.getServer().getLevel(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int last(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        LastQueryManager.State st = LastQueryManager.get(sp);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Нет активного запроса логов. Сначала выполните /log i или /log --time 30m."));
            return 0;
        }
        ServerLevel lvl = resolveLevelForDim(ctx.getSource(), st.baseQuery.dim);
        if (lvl == null) lvl = sp.serverLevel();

        ChatLogPager.renderAndSend(lvl, sp, st);
        return 1;
    }

    private static int rollbackRadius(CommandContext<CommandSourceStack> ctx, int seconds, int radius, String actor) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        ServerLevel level = sp.serverLevel();
        BlockPos center = sp.blockPosition();
        long sinceTs = System.currentTimeMillis() - (seconds * 1000L);

        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        int changed = RollbackEngine.rollbackBox(level, min, max, sinceTs, actor);
        ctx.getSource().sendSuccess(() -> Component.literal("Rollback complete. affected=" + changed), true);
        return 1;
    }

    private static int rollbackDateRadius(CommandContext<CommandSourceStack> ctx, String date, int radius, String actor) {
        long[] range = parseDayRangeMillis(date);
        if (range == null) {
            ctx.getSource().sendFailure(Component.literal("Неверная дата. Формат: yyyy-MM-dd (например 2026-01-25) или dd.MM.yyyy (например 25.01.2026)"));
            return 0;
        }
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        ServerLevel level = sp.serverLevel();
        BlockPos center = sp.blockPosition();
        BlockPos min = center.offset(-radius, -radius, -radius);
        BlockPos max = center.offset(radius, radius, radius);

        int changed = RollbackEngine.rollbackBoxRange(level, min, max, range[0], range[1], actor);
        ctx.getSource().sendSuccess(() -> Component.literal("Rollback complete. affected=" + changed + " (date=" + date + ")"), true);
        return 1;
    }

    private static int rollbackDateAll(CommandContext<CommandSourceStack> ctx, String date, String actor) {
        long[] range = parseDayRangeMillis(date);
        if (range == null) {
            ctx.getSource().sendFailure(Component.literal("Неверная дата. Формат: yyyy-MM-dd (например 2026-01-25) или dd.MM.yyyy (например 25.01.2026)"));
            return 0;
        }
        if (ctx.getSource().getServer() == null) {
            ctx.getSource().sendFailure(Component.literal("Сервер недоступен."));
            return 0;
        }
        int changed = 0;
        for (ServerLevel lvl : ctx.getSource().getServer().getAllLevels()) {
            // World border hard limits (safe integers, avoids DB overflow).
            BlockPos min = new BlockPos(-30_000_000, -2048, -30_000_000);
            BlockPos max = new BlockPos(30_000_000, 4096, 30_000_000);
            changed += RollbackEngine.rollbackBoxRange(lvl, min, max, range[0], range[1], actor);
        }
        final int affected = changed;
        final String dateStr = date;
        ctx.getSource().sendSuccess(() -> Component.literal("Rollback complete. affected=" + affected + " (date=" + dateStr + ", all dims)"), true);
        return 1;
    }

    private static int rollbackBlock(CommandContext<CommandSourceStack> ctx, int seconds) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        ServerLevel level = sp.serverLevel();
        BlockHitResult hit = RayTraceUtil.getPlayerPOVHitResult(sp, level, 6.0);
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal("No block targeted."));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        long sinceTs = System.currentTimeMillis() - (seconds * 1000L);

        // If chest: rollback both halves if double chest.
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            BlockPos other = ChestUtil.getConnectedChestPos(level, pos, state);
            int c1 = RollbackEngine.rollbackExact(level, pos, sinceTs, null);
            int c2 = other != null && !other.equals(pos) ? RollbackEngine.rollbackExact(level, other, sinceTs, null) : 0;
            ctx.getSource().sendSuccess(() -> Component.literal("Rollback chest complete. affected=" + (c1 + c2)), true);
            return 1;
        }

        int changed = RollbackEngine.rollbackExact(level, pos, sinceTs, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Rollback block complete. affected=" + changed), true);
        return 1;
    }

    private static int rollbackWorldEditSelection(CommandContext<CommandSourceStack> ctx, int seconds) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        ServerLevel level = sp.serverLevel();
        BlockPos[] sel = WorldEditIntegration.getSelection(sp);
        if (sel == null) {
            ctx.getSource().sendFailure(Component.literal("WorldEdit selection not found. Make sure WorldEdit is installed and you have a selection."));
            return 0;
        }
        long sinceTs = System.currentTimeMillis() - (seconds * 1000L);
        int changed = RollbackEngine.rollbackBox(level, sel[0], sel[1], sinceTs, null);
        ctx.getSource().sendSuccess(() -> Component.literal("Rollback WorldEdit selection complete. affected=" + changed), true);
        return 1;
    }

    private static int setInspectTool(CommandContext<CommandSourceStack> ctx, String itemId) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        var rl = net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null) {
            ctx.getSource().sendFailure(Component.literal("Неверный id предмета: " + itemId));
            return 0;
        }
        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl) == net.minecraft.world.item.Items.AIR) {
            // AIR is returned for unknown ids in some mappings
            ctx.getSource().sendFailure(Component.literal("Предмет не найден: " + itemId));
            return 0;
        }
        LoggerServerData.get(level).setInspectToolItemId(itemId);
        ctx.getSource().sendSystemMessage(Component.literal("Предмет инспектора установлен: " + itemId));
        return 1;
    }

    private static int resetInspectTool(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        LoggerServerData.get(level).resetInspectToolItemId();
        ctx.getSource().sendSystemMessage(Component.literal("Предмет инспектора сброшен к значению из конфига: " + LoggerConfig.inspectToolItemId()));
        return 1;
    }


    private static int lookupAll(CommandContext<CommandSourceStack> ctx, int seconds, String actor) {
        if (seconds <= 0) {
            ctx.getSource().sendFailure(Component.literal("Неверный интервал времени. Используйте число секунд или суффиксы s/m/h/d, например: 30s, 10m, 2h, 1d"));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }

        LogQuery q = new LogQuery();
        q.dim = "*"; // all dimensions
        q.sinceTs = System.currentTimeMillis() - (seconds * 1000L);
        q.untilTs = System.currentTimeMillis();
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;

        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
            String title = "Игрок: " + (actor == null ? "все" : actor) + " за " + formatDuration(seconds);
            LastQueryManager.State st = LastQueryManager.set(sp, q, title);
            ChatLogPager.renderAndSend(level, sp, st);
            return 1;
        }

        q.limit = Math.min(200, LoggerConfig.VALUES.lookupDefaultLimit.get());
        List<LogEntry> entries = LoggerRuntime.storage(level).queryReverse(q);
        ctx.getSource().sendSystemMessage(Component.literal("--- Игрок: " + (actor == null ? "все" : actor) + " (" + formatDuration(seconds) + ") ---"));
        if (entries.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("(нет записей)"));
            return 1;
        }
        for (LogEntry e : entries) {
            if (e != null && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && e.extra != null && e.extra.startsWith("container change")) continue;
            ctx.getSource().sendSystemMessage(LogText.toChatLine(level, e));
        }
        return 1;
    }

    private static int lookupDate(CommandContext<CommandSourceStack> ctx, String date, int radius, String actor) {
        long[] range = parseDayRangeMillis(date);
        if (range == null) {
            ctx.getSource().sendFailure(Component.literal("Неверная дата. Формат: yyyy-MM-dd (например 2026-01-25) или dd.MM.yyyy (например 25.01.2026)"));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        BlockPos center;
        if (ctx.getSource().getEntity() != null) {
            center = ctx.getSource().getEntity().blockPosition();
        } else {
            center = BlockPos.ZERO;
        }

        LogQuery q = new LogQuery();
        q.dim = radius <= 0 ? "*" : level.dimension().location().toString();
        q.sinceTs = range[0];
        q.untilTs = range[1];
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;
        if (radius > 0) {
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
        } else {
            q.exactPos = null;
            q.minPos = null;
            q.maxPos = null;
        }

        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
            String title = "Поиск дата=" + date + ", r=" + radius;
            LastQueryManager.State st = LastQueryManager.set(sp, q, title);
            ChatLogPager.renderAndSend(level, sp, st);
            return 1;
        }

        q.limit = Math.min(200, LoggerConfig.VALUES.lookupDefaultLimit.get());
        List<LogEntry> entries = LoggerRuntime.storage(level).queryReverse(q);
        ctx.getSource().sendSystemMessage(Component.literal("--- Поиск (дата=" + date + ", r=" + radius + ") ---"));
        if (entries.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("(нет записей)"));
            return 1;
        }
        for (LogEntry e : entries) {
            if (e != null && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && e.extra != null && e.extra.startsWith("container change")) continue;
            ctx.getSource().sendSystemMessage(LogText.toChatLine(level, e));
        }
        return 1;
    }

    private static int lookupDateAll(CommandContext<CommandSourceStack> ctx, String date, String actor) {
        long[] range = parseDayRangeMillis(date);
        if (range == null) {
            ctx.getSource().sendFailure(Component.literal("Неверная дата. Формат: yyyy-MM-dd (например 2026-01-25) или dd.MM.yyyy (например 25.01.2026)"));
            return 0;
        }
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }

        LogQuery q = new LogQuery();
        q.dim = "*"; // all dimensions
        q.sinceTs = range[0];
        q.untilTs = range[1];
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;

        if (ctx.getSource().getEntity() instanceof ServerPlayer sp) {
            String title = "Поиск дата=" + date + " (все миры)";
            LastQueryManager.State st = LastQueryManager.set(sp, q, title);
            ChatLogPager.renderAndSend(level, sp, st);
            return 1;
        }

        q.limit = Math.min(200, LoggerConfig.VALUES.lookupDefaultLimit.get());
        List<LogEntry> entries = LoggerRuntime.storage(level).queryReverse(q);
        ctx.getSource().sendSystemMessage(Component.literal("--- Поиск (дата=" + date + ", все миры) ---"));
        if (entries.isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("(нет записей)"));
            return 1;
        }
        for (LogEntry e : entries) {
            if (e != null && e.type == ActionType.BLOCK_ENTITY_NBT_CHANGE && e.extra != null && e.extra.startsWith("container change")) continue;
            ctx.getSource().sendSystemMessage(LogText.toChatLine(level, e));
        }
        return 1;
    }

    private static int lookupPlayerAll(CommandContext<CommandSourceStack> ctx, String name, int seconds) {
        return lookupAll(ctx, seconds, name);
    }

    // ---------------------------------------------------------------------
    // Flag-based workflow (variants A/B)
    // ---------------------------------------------------------------------

    /**
     * Default /log execution (no args): show recent events around the executor.
     */
    private static int lookupDefaultRoot(CommandContext<CommandSourceStack> ctx) {
        if (!hasLookupPermission(ctx.getSource())) {
            ctx.getSource().sendFailure(Component.literal("Нет прав на просмотр логов (avilixlogger.command.lookup / avilixlogger.command.use)."));
            return 0;
        }

        ctx.getSource().sendSystemMessage(Component.literal("Подсказка: /log help — примеры фильтров и откатов."));
        // Keep backward-compatible behaviour: short local lookup.
        return lookup(ctx, 30 * 60, 5, null);
    }

    private static int help(CommandContext<CommandSourceStack> ctx) {
        sendShortHelp(ctx.getSource());
        return 1;
    }

    private static void sendShortHelp(CommandSourceStack src) {
        src.sendSystemMessage(Component.literal("AvilixLogger — /log").withStyle(ChatFormatting.GOLD));

        src.sendSystemMessage(Component.literal("Просмотр").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  /log").withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log --t 30m --r 10 --m theft").withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log --d 2026-01-25 --r 20 --p Steve").withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log --d 2026-01-25 --ad   (все миры)" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log i [флаги...]   |   /log last [флаги...]" ).withStyle(ChatFormatting.GRAY));

        src.sendSystemMessage(Component.literal("Откат").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  /log rollback --t 2h --r 30 --m grief   (состояние 2 часа назад)" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log rollback --at 2026-08-27T12:30 --we   (состояние на точный момент)" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log rollback --confirm   |   /log rollback --cancel" ).withStyle(ChatFormatting.GRAY));

        src.sendSystemMessage(Component.literal("Флаги (коротко)").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  --t time | --at yyyy-MM-ddTHH:mm | --d date (00:00) | --r radius | --we | --block" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  mode: grief | theft | combat | planes | all   |   types: --ty break,place,container_take" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  dim: overworld | nether | end   (или полный id: minecraft:overworld)" ).withStyle(ChatFormatting.GRAY));

        src.sendSystemMessage(Component.literal("Пресеты").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  /log preset save <name> <args...>   |   /log preset run <name> [args...]" ).withStyle(ChatFormatting.GRAY));
    }

    private static final class ParsedFlags {
        Integer seconds;           // from --time
        long[] dayRange;           // from --date/--day
        Long targetMoment;         // from --at (absolute server time)
        Integer radius;            // from --radius
        boolean allDims;           // --all-dims
        String dim;                // --dim
        String actor;              // --player
        String owner;             // --owner (planes)
        boolean world;            // --world/--global (ignore radius and search whole world)
        EnumSet<ActionType> types; // --types or derived from --mode
        String mode;               // --mode
        Integer page;              // --page
        Integer limit;             // --limit
        String preset;             // --preset
        boolean confirm;           // --confirm
        boolean cancel;            // --cancel
        boolean weSelection;       // --we (use WorldEdit selection)
        boolean targetBlock;       // --block (targeted block)

        ParsedFlags copy() {
            ParsedFlags p = new ParsedFlags();
            p.seconds = this.seconds;
            p.dayRange = this.dayRange;
            p.targetMoment = this.targetMoment;
            p.radius = this.radius;
            p.allDims = this.allDims;
            p.dim = this.dim;
            p.actor = this.actor;
            p.owner = this.owner;
            p.world = this.world;
            p.types = this.types != null ? this.types.clone() : null;
            p.mode = this.mode;
            p.page = this.page;
            p.limit = this.limit;
            p.preset = this.preset;
            p.confirm = this.confirm;
            p.cancel = this.cancel;
            p.weSelection = this.weSelection;
            p.targetBlock = this.targetBlock;
            return p;
        }
    }

    private static ParsedFlags parseFlagsRaw(String raw, java.util.List<String> errors) {
        ParsedFlags out = new ParsedFlags();
        if (raw == null) return out;
        String s = raw.trim();
        if (s.isEmpty()) return out;

        String[] parts = s.split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String tok = parts[i];
            if (tok == null || tok.isBlank()) continue;

            if (!tok.startsWith("--")) {
                errors.add("Неизвестный аргумент: " + tok);
                continue;
            }

            String key = tok.substring(2);
            String val = null;
            int eq = key.indexOf('=');
            if (eq >= 0) {
                val = key.substring(eq + 1);
                key = key.substring(0, eq);
            } else {
                // Take next token as a value if it is not another flag.
                if (i + 1 < parts.length && !parts[i + 1].startsWith("--")) {
                    val = parts[++i];
                }
            }

            String k = key.toLowerCase(Locale.ROOT);
            switch (k) {
                case "time", "t" -> {
                    if (val == null) { errors.add("--time требует значение (например 30m)"); break; }
                    int sec = parseDurationSeconds(val);
                    if (sec <= 0) errors.add("Неверный --time: " + val);
                    else out.seconds = sec;
                }
                case "date", "day", "d" -> {
                    if (val == null) { errors.add("--date требует значение (например 2026-01-25)"); break; }
                    long[] r = parseDayRangeMillis(val);
                    if (r == null) errors.add("Неверный --date: " + val);
                    else out.dayRange = r;
                }
                case "at", "moment", "to" -> {
                    if (val == null) { errors.add("--at требует момент, например 2026-08-27T12:30"); break; }
                    Long target = parseTargetMomentMillis(val);
                    if (target == null) errors.add("Неверный --at: " + val + " (ожидается yyyy-MM-ddTHH:mm)");
                    else out.targetMoment = target;
                }
                case "radius", "r" -> {
                    if (val == null) { errors.add("--radius требует число"); break; }
                    try {
                        int rr = Integer.parseInt(val);
                        if (rr < 0 || rr > 256) errors.add("--radius вне диапазона 0..256");
                        else out.radius = rr;
                    } catch (Exception ex) {
                        errors.add("Неверный --radius: " + val);
                    }
                }
                case "all-dims", "alldims", "all", "ad" -> out.allDims = true;
                case "dim", "di" -> {
                    if (val == null) { errors.add("--dim требует id измерения"); break; }
                    out.dim = normalizeDimToken(val);
                }
                case "player", "actor", "p" -> {
                    if (val == null) { errors.add("--player требует ник"); break; }
                    out.actor = val;
                }
                case "owner", "o" -> {
                    if (val == null) { errors.add("--owner требует ник или uuid"); break; }
                    out.owner = val;
                }
                case "world", "global", "w" -> out.world = true;
                case "mode", "m" -> {
                    if (val == null) { errors.add("--mode требует значение: grief|theft|combat|planes|all"); break; }
                    out.mode = val;
                }
                case "types", "ty" -> {
                    if (val == null) { errors.add("--types требует список через запятую"); break; }
                    EnumSet<ActionType> set = parseTypesCsv(val, errors);
                    if (set != null && !set.isEmpty()) out.types = set;
                }
                case "limit", "l" -> {
                    if (val == null) { errors.add("--limit требует число"); break; }
                    try {
                        int lim = Integer.parseInt(val);
                        if (lim < 1 || lim > 200) errors.add("--limit вне диапазона 1..200");
                        else out.limit = lim;
                    } catch (Exception ex) {
                        errors.add("Неверный --limit: " + val);
                    }
                }
                case "page", "pg" -> {
                    if (val == null) { errors.add("--page требует число"); break; }
                    try {
                        int p = Integer.parseInt(val);
                        if (p < 1 || p > 100000) errors.add("--page вне диапазона");
                        else out.page = p;
                    } catch (Exception ex) {
                        errors.add("Неверный --page: " + val);
                    }
                }
                case "preset", "pr" -> {
                    if (val == null) { errors.add("--preset требует имя"); break; }
                    out.preset = val;
                }
                case "confirm", "c" -> out.confirm = true;
                case "cancel" -> out.cancel = true;
                case "we", "worldedit" -> out.weSelection = true;
                case "block", "target", "target-block" -> out.targetBlock = true;
                default -> errors.add("Неизвестный флаг: --" + key);
            }
        }

        // Sanity: disallow mixing absolute date range and relative time.
        int timeSelectors = (out.seconds != null ? 1 : 0) + (out.dayRange != null ? 1 : 0) + (out.targetMoment != null ? 1 : 0);
        if (timeSelectors > 1) {
            errors.add("Нельзя смешивать --time, --date и --at. Выберите один целевой момент.");
        }
        if (out.confirm && out.cancel) {
            errors.add("Нельзя одновременно подтвердить и отменить план.");
        }
        return out;
    }

    private static String normalizeDimToken(String val) {
        if (val == null) return null;
        String v = val.trim();
        if (v.isEmpty()) return v;
        String l = v.toLowerCase(Locale.ROOT);
        if (!l.contains(":")) {
            // Human aliases
            if (l.equals("overworld") || l.equals("world") || l.equals("main")) return "minecraft:overworld";
            if (l.equals("nether") || l.equals("hell")) return "minecraft:the_nether";
            if (l.equals("end") || l.equals("theend")) return "minecraft:the_end";
        }
        return v;
    }

    private static EnumSet<ActionType> parseTypesCsv(String csv, java.util.List<String> errors) {
        if (csv == null || csv.isBlank()) return null;
        EnumSet<ActionType> set = EnumSet.noneOf(ActionType.class);
        String[] parts = csv.split(",");
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            ActionType at = mapTypeToken(t);
            if (at == null) {
                errors.add("Неизвестный тип действия: " + t);
            } else {
                set.add(at);
            }
        }
        return set;
    }

    private static ActionType mapTypeToken(String token) {
        if (token == null || token.isBlank()) return null;
        String t = token.trim().toLowerCase(Locale.ROOT);
        return switch (t) {
            case "break", "block_break" -> ActionType.BLOCK_BREAK;
            case "place", "block_place" -> ActionType.BLOCK_PLACE;
            case "interact", "use", "block_interact" -> ActionType.BLOCK_INTERACT;
            case "open", "container_open" -> ActionType.CONTAINER_OPEN;
            case "nbt", "be", "block_entity" -> ActionType.BLOCK_ENTITY_NBT_CHANGE;
            case "entity_death", "kill", "death" -> ActionType.ENTITY_DEATH;
            case "entity_spawn", "spawn" -> ActionType.ENTITY_SPAWN;
            case "item_drop", "drop" -> ActionType.ITEM_DROP;
            case "item_pickup", "pickup" -> ActionType.ITEM_PICKUP;
            case "craft", "item_craft" -> ActionType.ITEM_CRAFT;
            case "smelt", "item_smelt" -> ActionType.ITEM_SMELT;
            case "item_use", "use_item" -> ActionType.ITEM_USE;
            case "item_use_start", "use_start", "charge", "draw", "start_use" -> ActionType.ITEM_USE_START;
            case "item_use_stop", "use_stop", "release", "stop_use" -> ActionType.ITEM_USE_STOP;
            case "item_consume", "consume", "drink", "eat" -> ActionType.ITEM_CONSUME;
            case "projectile_shoot", "shoot", "shot", "bow", "crossbow", "arrow" -> ActionType.PROJECTILE_SHOOT;
            case "projectile_hit", "hit", "arrow_hit", "impact" -> ActionType.PROJECTILE_HIT;
            case "gui", "gui_open", "menu", "interface" -> ActionType.GUI_OPEN;
            case "player_death" -> ActionType.PLAYER_DEATH;
            case "join", "player_join" -> ActionType.PLAYER_JOIN;
            case "leave", "quit", "player_leave" -> ActionType.PLAYER_LEAVE;
            case "put", "container_put" -> ActionType.CONTAINER_PUT;
            case "take", "container_take" -> ActionType.CONTAINER_TAKE;
            default -> {
                try {
                    yield ActionType.valueOf(token.trim().toUpperCase(Locale.ROOT));
                } catch (Exception ex) {
                    yield null;
                }
            }
        };
    }

    private static EnumSet<ActionType> typesForMode(String mode) {
        if (mode == null || mode.isBlank()) return null;
        String m = mode.trim().toLowerCase(Locale.ROOT);
        return switch (m) {
            case "grief", "build" -> EnumSet.of(ActionType.BLOCK_BREAK, ActionType.BLOCK_PLACE, ActionType.BLOCK_INTERACT);
            case "theft", "steal" -> EnumSet.of(ActionType.CONTAINER_OPEN, ActionType.CONTAINER_PUT, ActionType.CONTAINER_TAKE, ActionType.ITEM_PICKUP, ActionType.ITEM_DROP, ActionType.GUI_OPEN);
            case "combat", "pvp" -> EnumSet.of(ActionType.ENTITY_DEATH, ActionType.PLAYER_DEATH, ActionType.ENTITY_SPAWN, ActionType.ENTITY_ATTACK, ActionType.PROJECTILE_SHOOT, ActionType.PROJECTILE_HIT);
            case "items", "item" -> EnumSet.of(ActionType.ITEM_DROP, ActionType.ITEM_PICKUP, ActionType.ITEM_CRAFT, ActionType.ITEM_SMELT, ActionType.ITEM_USE, ActionType.ITEM_USE_START, ActionType.ITEM_USE_STOP, ActionType.ITEM_CONSUME, ActionType.PROJECTILE_SHOOT, ActionType.PROJECTILE_HIT);
            case "gui", "menus", "interfaces" -> EnumSet.of(ActionType.GUI_OPEN, ActionType.CONTAINER_OPEN, ActionType.ENTITY_CONTAINER_OPEN);
            case "planes", "plane", "aircraft" -> EnumSet.of(
                    ActionType.PLANE_PLACE, ActionType.PLANE_REMOVE, ActionType.PLANE_MOUNT, ActionType.PLANE_PICKUP, ActionType.ENTITY_OWNER_SET,
                    ActionType.ENTITY_SPAWN, ActionType.ENTITY_DEATH, ActionType.ENTITY_MOUNT, ActionType.ENTITY_DISMOUNT, ActionType.ENTITY_INTERACT
            );
            case "all" -> null;
            default -> null;
        };
    }

    private static ParsedFlags parseFlagsWithPresets(CommandContext<CommandSourceStack> ctx, ParsedFlags direct, java.util.List<String> errors) {
        // If --preset is provided, load it from SavedData and merge: preset first, direct overrides.
        if (direct == null) direct = new ParsedFlags();
        if (direct.preset == null || direct.preset.isBlank()) return direct;
        if (!(ctx.getSource().getLevel() instanceof ServerLevel lvl)) return direct;

        LoggerServerData data = LoggerServerData.get(lvl);
        String presetArgs = data.getPreset(direct.preset);
        if (presetArgs == null || presetArgs.isBlank()) {
            errors.add("Пресет не найден: " + direct.preset);
            return direct;
        }
        java.util.ArrayList<String> presetErrors = new java.util.ArrayList<>();
        ParsedFlags base = parseFlagsRaw(presetArgs, presetErrors);
        // Merge: direct overrides base
        ParsedFlags merged = base;
        if (direct.seconds != null) {
            merged.seconds = direct.seconds;
            merged.dayRange = null;
            merged.targetMoment = null;
        }
        if (direct.dayRange != null) {
            merged.seconds = null;
            merged.dayRange = direct.dayRange;
            merged.targetMoment = null;
        }
        if (direct.targetMoment != null) {
            merged.seconds = null;
            merged.dayRange = null;
            merged.targetMoment = direct.targetMoment;
        }
        if (direct.radius != null) merged.radius = direct.radius;
        if (direct.allDims) merged.allDims = true;
        if (direct.dim != null) merged.dim = direct.dim;
        if (direct.actor != null) merged.actor = direct.actor;
        if (direct.owner != null) merged.owner = direct.owner;
        if (direct.world) merged.world = true;
        if (direct.types != null) merged.types = direct.types;
        if (direct.mode != null) merged.mode = direct.mode;
        if (direct.page != null) merged.page = direct.page;
        if (direct.limit != null) merged.limit = direct.limit;
        merged.preset = direct.preset;
        // Safety actions can never be inherited from a stored preset.
        merged.confirm = direct.confirm;
        merged.cancel = direct.cancel;
        merged.weSelection = direct.weSelection || base.weSelection;
        merged.targetBlock = direct.targetBlock || base.targetBlock;
        if (!presetErrors.isEmpty()) {
            errors.addAll(presetErrors);
        }
        return merged;
    }

    private static int lookupFromFlags(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Флаговый поиск доступен только игроку (для пагинации)."));
            return 0;
        }

        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        ParsedFlags direct = parseFlagsRaw(rawArgs, errors);
        ParsedFlags f = parseFlagsWithPresets(ctx, direct, errors);

        if (f.types == null) {
            EnumSet<ActionType> byMode = typesForMode(f.mode);
            if (byMode != null) f.types = byMode;
        }

        if (!errors.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(String.join("; ", errors)));
            sendShortHelp(ctx.getSource());
            return 0;
        }

        int seconds = f.seconds != null ? f.seconds : 30 * 60;
        int radius = f.radius != null ? f.radius : 5;

        boolean worldLookup = f.world || (f.radius != null && f.radius <= 0);

        LogQuery q = new LogQuery();
        if (f.allDims) q.dim = "*";
        else if (f.dim != null && !f.dim.isBlank()) q.dim = f.dim;
        else q.dim = level.dimension().location().toString();

        if (f.targetMoment != null) {
            q.sinceTs = f.targetMoment;
            q.untilTs = System.currentTimeMillis();
        } else if (f.dayRange != null) {
            q.sinceTs = f.dayRange[0];
            q.untilTs = f.dayRange[1];
        } else {
            q.sinceTs = System.currentTimeMillis() - (seconds * 1000L);
            q.untilTs = System.currentTimeMillis();
        }

        q.limit = f.limit != null ? Math.max(1, Math.min(200, f.limit)) : LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (f.actor != null && !f.actor.isBlank()) q.actorName = f.actor;
        if (f.types != null && !f.types.isEmpty()) q.types = f.types;
        if (f.owner != null && !f.owner.isBlank()) q.owner = f.owner;
        q.debugSource = "command";

        if (!worldLookup) {
            BlockPos center = sp.blockPosition();
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
        } else {
            // World/global search: no position bounding box.
            q.exactPos = null;
            q.minPos = null;
            q.maxPos = null;
        }

        String title;
        if (f.targetMoment != null) {
            title = "Поиск после " + formatRollbackMoment(f.targetMoment) + ", r=" + radius;
        } else if (f.dayRange != null) {
            title = "Поиск дата=" + rawDateForTitle(f.dayRange) + ", r=" + radius;
        } else {
            title = "Поиск " + formatDuration(seconds) + ", r=" + radius;
        }
        if (f.mode != null && !f.mode.isBlank()) title += ", mode=" + f.mode;
        if (worldLookup) title += ", world";
        if (f.owner != null && !f.owner.isBlank()) title += ", owner=" + f.owner;

        LastQueryManager.State st = LastQueryManager.set(sp, q, title);

        ServerLevel renderLevel = resolveLevelForDim(ctx.getSource(), q.dim);
        if (renderLevel == null) renderLevel = sp.serverLevel();
        ChatLogPager.renderAndSend(renderLevel, sp, st);

        if (f.page != null && f.page > 1) {
            return pageTo(ctx, f.page);
        }
        return 1;
    }

    // Title helper: we do not store the original date string, so use a simple marker.
    private static String rawDateForTitle(long[] range) {
        try {
            ZoneId z = ZoneId.systemDefault();
            LocalDate d = java.time.Instant.ofEpochMilli(range[0]).atZone(z).toLocalDate();
            return d.toString();
        } catch (Exception ignored) {
            return "?";
        }
    }

    private static int rollbackFromFlags(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }

        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        ParsedFlags direct = parseFlagsRaw(rawArgs, errors);
        ParsedFlags f = parseFlagsWithPresets(ctx, direct, errors);
        if (!errors.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(String.join("; ", errors)));
            return 0;
        }

        // Confirmation and cancellation intentionally use no recalculated arguments.
        if (f.cancel) {
            RollbackPlanManager.Plan removed = RollbackPlanManager.clear(sp.getUUID());
            RollbackCoordinator.CancelResult cancelled = RollbackCoordinator.cancel(sp.getUUID());
            clearRollbackPreview(sp);
            if (removed == null && cancelled == RollbackCoordinator.CancelResult.NONE) {
                ctx.getSource().sendFailure(Component.literal("Нет активного плана отката."));
                return 0;
            }
            if (cancelled == RollbackCoordinator.CancelResult.ACTIVE) {
                ctx.getSource().sendSuccess(() -> Component.literal("Активный откат остановлен. Уже применённые порции не отменены.")
                        .withStyle(ChatFormatting.YELLOW), false);
            } else if (cancelled == RollbackCoordinator.CancelResult.PREPARATION) {
                ctx.getSource().sendSuccess(() -> Component.literal("Подготовка отката отменена.").withStyle(ChatFormatting.YELLOW), false);
            } else {
                ctx.getSource().sendSuccess(() -> Component.literal("План отката #" + removed.id() + " отменён.").withStyle(ChatFormatting.YELLOW), false);
            }
            return 1;
        }
        if (f.confirm) {
            RollbackPlanManager.Plan plan = RollbackPlanManager.getValid(sp.getUUID());
            if (plan == null) {
                clearRollbackPreview(sp);
                ctx.getSource().sendFailure(Component.literal("Нет активного предпросмотра или он истёк. Сначала создайте новый план без --confirm."));
                return 0;
            }

            // Consume before applying so a repeated command cannot execute the same plan twice.
            RollbackPlanManager.clear(sp.getUUID());
            clearRollbackPreview(sp);
            CommandSourceStack src = ctx.getSource();
            src.sendSystemMessage(Component.literal("План #" + plan.id() + " принят. ClickHouse готовит точный список операций вне серверного тика…")
                    .withStyle(ChatFormatting.YELLOW));
            RollbackCoordinator.startRollback(src.getServer(), sp.getUUID(), plan,
                    () -> src.sendSystemMessage(Component.literal("План подготовлен. Откат выполняется небольшими порциями; сервер продолжает работать.")
                            .withStyle(ChatFormatting.AQUA)),
                    total -> {
                        src.sendSystemMessage(Component.literal("План #" + plan.id() + ", состояние на "
                                + formatRollbackMoment(plan.targetTs())).withStyle(ChatFormatting.DARK_GRAY));
                        sendRollbackSummary(src, true, total);
                    },
                    message -> src.sendFailure(Component.literal(message)));
            return 1;
        }

        if (f.types == null) {
            EnumSet<ActionType> byMode = typesForMode(f.mode);
            if (byMode != null) f.types = byMode;
        }
        if (f.seconds == null && f.dayRange == null && f.targetMoment == null) {
            ctx.getSource().sendFailure(Component.literal("Укажите целевой момент: --time 2h, --at 2026-08-27T12:30 или --date 2026-08-27."));
            return 0;
        }
        if (ctx.getSource().getServer() == null) {
            ctx.getSource().sendFailure(Component.literal("Сервер недоступен."));
            return 0;
        }

        final long cutoffTs = System.currentTimeMillis();
        final long targetTs = f.targetMoment != null
                ? f.targetMoment
                : (f.dayRange != null ? f.dayRange[0] : cutoffTs - (f.seconds * 1000L));
        if (targetTs <= 0L || targetTs >= cutoffTs) {
            ctx.getSource().sendFailure(Component.literal("Целевой момент должен находиться в прошлом."));
            return 0;
        }

        List<RollbackPlanManager.Scope> scopes = buildRollbackScopes(ctx.getSource(), sp, f);
        if (scopes == null || scopes.isEmpty()) return 0;
        if (RollbackCoordinator.hasActiveRollback()) {
            ctx.getSource().sendFailure(Component.literal("Сейчас уже выполняется откат. Дождитесь завершения или остановите его через --cancel."));
            return 0;
        }

        RollbackPlanManager.clear(sp.getUUID());
        RollbackCoordinator.cancelPreparation(sp.getUUID());
        clearRollbackPreview(sp);
        String actor = f.actor != null && !f.actor.isBlank() ? f.actor : null;
        for (RollbackPlanManager.Scope scope : scopes) {
            ServerLevel level = resolveLevelForDim(ctx.getSource(), scope.dimension());
            if (level == null) {
                ctx.getSource().sendFailure(Component.literal("Измерение недоступно: " + scope.dimension()));
                return 0;
            }
        }

        RollbackPlanManager.Plan plan = RollbackPlanManager.save(
                sp.getUUID(), targetTs, cutoffTs, actor, f.types, scopes);
        if (plan == null) {
            ctx.getSource().sendFailure(Component.literal("Не удалось сохранить план отката."));
            return 0;
        }

        CommandSourceStack src = ctx.getSource();
        showRollbackPreview(sp, plan);
        src.sendSystemMessage(Component.literal("Считаю точный предпросмотр в ClickHouse вне серверного тика…")
                .withStyle(ChatFormatting.YELLOW));
        RollbackCoordinator.preparePreview(src.getServer(), sp.getUUID(), plan,
                total -> {
                    RollbackPlanManager.Plan current = RollbackPlanManager.getValid(sp.getUUID());
                    if (current == null || !current.id().equals(plan.id())) return;
                    sendRollbackPlanSummary(src, plan, total);
                },
                message -> {
                    RollbackPlanManager.Plan current = RollbackPlanManager.getValid(sp.getUUID());
                    if (current != null && current.id().equals(plan.id())) RollbackPlanManager.clear(sp.getUUID());
                    clearRollbackPreview(sp);
                    src.sendFailure(Component.literal(message));
                });
        return 1;
    }

    private static List<RollbackPlanManager.Scope> buildRollbackScopes(CommandSourceStack src, ServerPlayer sp, ParsedFlags f) {
        java.util.ArrayList<RollbackPlanManager.Scope> scopes = new java.util.ArrayList<>();
        if (f.weSelection) {
            if (f.allDims || f.world || f.targetBlock) {
                src.sendFailure(Component.literal("--we нельзя смешивать с --all-dims, --world или --block."));
                return null;
            }
            BlockPos[] selection = WorldEditIntegration.getSelection(sp);
            if (selection == null) {
                src.sendFailure(Component.literal("WorldEdit-выделение не найдено."));
                return null;
            }
            scopes.add(new RollbackPlanManager.Scope(
                    sp.serverLevel().dimension().location().toString(), selection[0], selection[1]));
            return scopes;
        }

        if (f.targetBlock) {
            if (f.allDims || f.world) {
                src.sendFailure(Component.literal("--block нельзя смешивать с --all-dims или --world."));
                return null;
            }
            ServerLevel level = sp.serverLevel();
            BlockHitResult hit = RayTraceUtil.getPlayerPOVHitResult(sp, level, 6.0);
            if (hit.getType() != HitResult.Type.BLOCK) {
                src.sendFailure(Component.literal("Наведитесь на блок и повторите команду."));
                return null;
            }
            BlockPos min = hit.getBlockPos();
            BlockPos max = min;
            BlockState state = level.getBlockState(min);
            if (state.getBlock() instanceof ChestBlock) {
                BlockPos other = ChestUtil.getConnectedChestPos(level, min, state);
                if (other != null) max = other;
            }
            scopes.add(new RollbackPlanManager.Scope(level.dimension().location().toString(), min, max));
            return scopes;
        }

        BlockPos worldMin = new BlockPos(-30_000_000, -2048, -30_000_000);
        BlockPos worldMax = new BlockPos(30_000_000, 4096, 30_000_000);
        if (f.allDims) {
            for (ServerLevel level : src.getServer().getAllLevels()) {
                scopes.add(new RollbackPlanManager.Scope(level.dimension().location().toString(), worldMin, worldMax));
            }
            return scopes;
        }

        ServerLevel level = sp.serverLevel();
        if (f.dim != null && !f.dim.isBlank()) {
            level = resolveLevelForDim(src, f.dim);
            if (level == null) {
                src.sendFailure(Component.literal("Измерение не найдено: " + f.dim));
                return null;
            }
        }
        int radius = f.radius != null ? f.radius : 5;
        if (f.world || radius == 0) {
            scopes.add(new RollbackPlanManager.Scope(level.dimension().location().toString(), worldMin, worldMax));
        } else {
            BlockPos center = sp.blockPosition();
            scopes.add(new RollbackPlanManager.Scope(
                    level.dimension().location().toString(),
                    center.offset(-radius, -radius, -radius),
                    center.offset(radius, radius, radius)));
        }
        return scopes;
    }

    private static void sendRollbackPlanSummary(CommandSourceStack src, RollbackPlanManager.Plan plan, RollbackReport report) {
        src.sendSystemMessage(Component.literal("Предпросмотр плана #" + plan.id()).withStyle(ChatFormatting.AQUA));
        src.sendSystemMessage(Component.literal("Цель: вернуть состояние на " + formatRollbackMoment(plan.targetTs()))
                .withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("Будут отменены действия после цели и до снимка предпросмотра "
                + formatRollbackMoment(plan.cutoffTs()) + ".").withStyle(ChatFormatting.GRAY));

        if (plan.scopes().size() == 1) {
            RollbackPlanManager.Scope scope = plan.scopes().get(0);
            src.sendSystemMessage(Component.literal("Область: " + scope.dimension() + " ["
                    + scope.min().getX() + " " + scope.min().getY() + " " + scope.min().getZ() + "] → ["
                    + scope.max().getX() + " " + scope.max().getY() + " " + scope.max().getZ() + "]")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            src.sendSystemMessage(Component.literal("Область: " + plan.scopes().size() + " измерений (глобальный план)")
                    .withStyle(ChatFormatting.GRAY));
        }

        sendRollbackSummary(src, false, report);
        long ttlSeconds = Math.max(1L, (plan.expiresAt() - System.currentTimeMillis()) / 1000L);
        src.sendSystemMessage(Component.literal("План действует " + ttlSeconds + " сек. Параметры и позиция уже зафиксированы.")
                .withStyle(ChatFormatting.DARK_GRAY));

        MutableComponent confirm = Component.literal("[ПОДТВЕРДИТЬ ОТКАТ]").withStyle(style -> style
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/log rollback --confirm")));
        MutableComponent cancel = Component.literal("  [ОТМЕНИТЬ]").withStyle(style -> style
                .withColor(ChatFormatting.RED)
                .withClickEvent(new net.minecraft.network.chat.ClickEvent(
                        net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND, "/log rollback --cancel")));
        src.sendSystemMessage(confirm.append(cancel));
    }

    private static String formatRollbackMoment(long timestamp) {
        try {
            return java.time.Instant.ofEpochMilli(timestamp)
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT));
        } catch (Throwable ignored) {
            return String.valueOf(timestamp);
        }
    }

    private static void showRollbackPreview(ServerPlayer player, RollbackPlanManager.Plan plan) {
        if (player == null || plan == null || !com.roften.avilixlogger.net.LoggerNetwork.isClientPresent(player)) return;
        if (plan.scopes().size() != 1) {
            clearRollbackPreview(player);
            player.sendSystemMessage(Component.literal("Глобальный план нельзя показать одной рамкой; точные измерения сохранены в плане.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        RollbackPlanManager.Scope scope = plan.scopes().get(0);
        // A full-world box is intentionally not sent to the renderer because its coordinates lose precision on the GPU.
        if (scope.min().getX() <= -1_000_000 || scope.max().getX() >= 1_000_000
                || scope.min().getZ() <= -1_000_000 || scope.max().getZ() >= 1_000_000) {
            clearRollbackPreview(player);
            player.sendSystemMessage(Component.literal("Откат всего измерения не имеет конечной отображаемой рамки.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(player,
                new com.roften.avilixlogger.net.S2CRollbackPreviewPayload(
                        true, plan.id(), scope.dimension(),
                        scope.min().getX(), scope.min().getY(), scope.min().getZ(),
                        scope.max().getX(), scope.max().getY(), scope.max().getZ(),
                        plan.targetTs(), plan.expiresAt()));
    }

    private static void clearRollbackPreview(ServerPlayer player) {
        if (player == null || !com.roften.avilixlogger.net.LoggerNetwork.isClientPresent(player)) return;
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(
                player, com.roften.avilixlogger.net.S2CRollbackPreviewPayload.clear());
    }


    private static void sendRollbackSummary(CommandSourceStack src, boolean applied, RollbackReport r) {
        if (src == null || r == null) return;

        Component title = Component.literal(applied ? "Откат выполнен" : "Предпросмотр отката")
                .withStyle(applied ? ChatFormatting.GREEN : ChatFormatting.AQUA);
        src.sendSystemMessage(title);

        String line1 = (applied
                ? ("Применено: " + r.applied + ", пропущено: " + r.skipped + ", всего: " + r.processed)
                : ("Будет применено: " + r.applied + ", будет пропущено: " + r.skipped + ", всего: " + r.processed));
        src.sendSystemMessage(Component.literal(line1).withStyle(ChatFormatting.GRAY));

        String line2 = "Блоки: " + r.blocksRestored
                + ", BE: " + r.blockEntitiesRestored
                + ", Контейнеры: " + r.containersRestored
                + ", Create: " + r.createStructuresRestored + " (" + r.createBlocksRestored + " блоков)"
                + ", Сущности: +" + r.entitiesRespawned + "/-" + r.entitiesRemoved
                + ", Предметы: +" + r.itemsGivenOrSpawned + "/-" + r.itemsRemovedFromInventory;
        src.sendSystemMessage(Component.literal(line2).withStyle(ChatFormatting.GRAY));

        String reasons = formatTopSkipReasons(r, 3);
        if (reasons != null && !reasons.isBlank()) {
            src.sendSystemMessage(Component.literal("Причины пропусков: " + reasons).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static String formatTopSkipReasons(RollbackReport r, int max) {
        if (r == null || r.skippedReasons == null || r.skippedReasons.isEmpty()) return "";
        java.util.ArrayList<java.util.Map.Entry<String, Integer>> list = new java.util.ArrayList<>(r.skippedReasons.entrySet());
        list.sort((a, b) -> Integer.compare(b.getValue() != null ? b.getValue() : 0, a.getValue() != null ? a.getValue() : 0));
        StringBuilder sb = new StringBuilder();
        int c = 0;
        for (var e : list) {
            if (e == null) continue;
            String k = e.getKey();
            Integer v = e.getValue();
            if (k == null || v == null || v <= 0) continue;
            if (c++ > 0) sb.append(", ");
            sb.append(translateSkipReason(k)).append(" x").append(v);
            if (c >= max) break;
        }
        return sb.toString();
    }

    private static String translateSkipReason(String key) {
        if (key == null) return "неизвестно";
        return switch (key) {
            case "chunk_unloaded" -> "чанк не загружен";
            case "no_snapshot" -> "нет снимка";
            case "no_entity_snapshot" -> "нет снимка сущности";
            case "unsafe_entity_snapshot" -> "опасная сущность Create/contraption";
            case "unknown_entity_type" -> "тип сущности не установлен";
            case "invalid_entity_nbt" -> "битый NBT сущности";
            case "incomplete_create_snapshot" -> "неполный снимок Create";
            case "entity_type_mismatch" -> "тип сущности не совпал";
            case "entity_missing_uuid" -> "в снимке нет UUID";
            case "entity_uuid_mismatch" -> "UUID снимка не совпал";
            case "entity_uuid_collision" -> "UUID уже существует в мире";
            case "entity_missing_position" -> "в снимке нет точной позиции";
            case "entity_missing_rotation" -> "в снимке нет поворота";
            case "entity_nbt_load_failed" -> "NBT сущности не загрузился";
            case "entity_add_rejected", "entity_add_incomplete", "entity_add_exception" -> "сущность не добавлена целиком";
            case "entity_remove_failed" -> "сущность не удалена целиком";
            case "invalid_block_snapshot" -> "битый снимок блока";
            case "invalid_block_entity_nbt" -> "битый NBT блока";
            case "invalid_container_snapshot" -> "битый снимок контейнера";
            case "block_state_verification_failed", "block_state_restore_failed" -> "блок не восстановлен";
            case "block_entity_restore_failed" -> "NBT блока не восстановлен";
            case "container_restore_failed" -> "контейнер не восстановлен целиком";
            case "create_nbt_load_failed" -> "Create не прочитал снимок";
            case "create_transform_unavailable" -> "нет преобразования Create";
            case "create_blocks_missing" -> "в снимке Create нет блоков";
            case "create_target_out_of_world" -> "Create выходит за границы мира";
            case "create_chunk_unloaded" -> "чанк Create не загружен";
            case "create_place_failed", "create_verification_failed", "create_restore_exception" -> "Create не восстановлен целиком";
            case "entity_not_found" -> "сущность не найдена";
            case "missing_uuid" -> "нет UUID";
            case "invalid_stack" -> "битый предмет";
            case "player_offline" -> "игрок не в сети";
            case "not_rollbackable" -> "не откатывается";
            case "exception" -> "ошибка";
            default -> key;
        };
    }

    private static String buildRollbackSummary(String label, RollbackReport r) {
        if (r == null) return label;
        String reasons = formatTopSkipReasons(r, 3);
        return label + ": applied=" + r.applied
                + ", skipped=" + r.skipped
                + ", processed=" + r.processed
                + " | blocks=" + r.blocksRestored
                + ", be=" + r.blockEntitiesRestored
                + ", containers=" + r.containersRestored
                + ", create=" + r.createStructuresRestored + "/" + r.createBlocksRestored
                + ", ent+=" + r.entitiesRespawned
                + ", ent-=" + r.entitiesRemoved
                + ", items+=" + r.itemsGivenOrSpawned
                + ", items-=" + r.itemsRemovedFromInventory
                + (reasons.isBlank() ? "" : " | skipped: " + reasons);
    }

    private static int inspectWithFlags(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        // Keep /log i behaviour and allow quick lookup overrides if the user passes flags.
        if (rawArgs != null && rawArgs.trim().startsWith("--")) {
            return lookupFromFlags(ctx, rawArgs);
        }
        return inspectBlock(ctx);
    }

    private static int lastWithFlags(CommandContext<CommandSourceStack> ctx, String rawArgs) {
        if (!(ctx.getSource().getEntity() instanceof ServerPlayer sp)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду может использовать только игрок."));
            return 0;
        }
        LastQueryManager.State st = LastQueryManager.get(sp);
        if (st == null) {
            ctx.getSource().sendFailure(Component.literal("Нет активного запроса логов. Сначала выполните /log i или /log --time 30m."));
            return 0;
        }
        LogQuery q = st.baseQuery.copy();

        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        ParsedFlags direct = parseFlagsRaw(rawArgs, errors);
        ParsedFlags f = parseFlagsWithPresets(ctx, direct, errors);
        if (f.types == null) {
            EnumSet<ActionType> byMode = typesForMode(f.mode);
            if (byMode != null) f.types = byMode;
        }
        if (!errors.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal(String.join("; ", errors)));
            sendShortHelp(ctx.getSource());
            return 0;
        }

        // Apply overrides onto last query
        boolean worldOverride = f.world || (f.radius != null && f.radius <= 0);
        if (f.allDims || (worldOverride && (f.dim == null || f.dim.isBlank()))) q.dim = "*";
        else if (f.dim != null && !f.dim.isBlank()) q.dim = f.dim;
        if (f.actor != null && !f.actor.isBlank()) q.actorName = f.actor;
        if (f.owner != null && !f.owner.isBlank()) q.owner = f.owner;
        if (f.limit != null) q.limit = Math.max(1, Math.min(200, f.limit));
        q.debugSource = "command-last";

        if (f.types != null && !f.types.isEmpty()) {
            q.types = f.types;
            q.type = null;
        }

        if (f.dayRange != null) {
            q.sinceTs = f.dayRange[0];
            q.untilTs = f.dayRange[1];
        } else if (f.seconds != null) {
            q.sinceTs = System.currentTimeMillis() - (f.seconds * 1000L);
            q.untilTs = System.currentTimeMillis();
        }

        if (worldOverride) {
            q.exactPos = null;
            q.minPos = null;
            q.maxPos = null;
        } else if (f.radius != null) {
            int radius = f.radius;
            BlockPos center = sp.blockPosition();
            q.exactPos = null;
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
        }

        String title = "Last (override)";
        LastQueryManager.State nst = LastQueryManager.set(sp, q, title);
        ServerLevel renderLevel = resolveLevelForDim(ctx.getSource(), q.dim);
        if (renderLevel == null) renderLevel = sp.serverLevel();
        ChatLogPager.renderAndSend(renderLevel, sp, nst);
        if (f.page != null && f.page > 1) return pageTo(ctx, f.page);
        return 1;
    }

    private static int presetSave(CommandContext<CommandSourceStack> ctx, String name, String args) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel level)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        if (name == null || name.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("Имя пресета пустое."));
            return 0;
        }
        if (args == null || args.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("Аргументы пресета пустые."));
            return 0;
        }
        java.util.ArrayList<String> errors = new java.util.ArrayList<>();
        ParsedFlags direct = parseFlagsRaw(args, errors);
        // Validate basic parse
        if (!errors.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("Пресет не сохранён: " + String.join("; ", errors)));
            return 0;
        }
        LoggerServerData data = LoggerServerData.get(level);
        boolean ok = data.setPreset(name, args);
        if (ok) ctx.getSource().sendSuccess(() -> Component.literal("Пресет сохранён: " + name), false);
        else ctx.getSource().sendFailure(Component.literal("Не удалось сохранить пресет."));
        return ok ? 1 : 0;
    }

    private static int presetRun(CommandContext<CommandSourceStack> ctx, String name, String extraArgs) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel lvl)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        LoggerServerData data = LoggerServerData.get(lvl);
        String p = data.getPreset(name);
        if (p == null || p.isBlank()) {
            ctx.getSource().sendFailure(Component.literal("Пресет не найден: " + name));
            return 0;
        }
        String merged = p;
        if (extraArgs != null && !extraArgs.isBlank()) merged = p + " " + extraArgs;
        return lookupFromFlags(ctx, merged);
    }

    private static int presetList(CommandContext<CommandSourceStack> ctx) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel lvl)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        LoggerServerData data = LoggerServerData.get(lvl);
        if (data.getPresets().isEmpty()) {
            ctx.getSource().sendSystemMessage(Component.literal("Пресетов нет."));
            return 1;
        }
        ctx.getSource().sendSystemMessage(Component.literal("Пресеты: " + String.join(", ", data.getPresets().keySet())));
        return 1;
    }

    private static int presetDelete(CommandContext<CommandSourceStack> ctx, String name) {
        if (!(ctx.getSource().getLevel() instanceof ServerLevel lvl)) {
            ctx.getSource().sendFailure(Component.literal("Эту команду можно использовать только в мире."));
            return 0;
        }
        LoggerServerData data = LoggerServerData.get(lvl);
        boolean ok = data.removePreset(name);
        if (ok) ctx.getSource().sendSuccess(() -> Component.literal("Пресет удалён: " + name), false);
        else ctx.getSource().sendFailure(Component.literal("Пресет не найден: " + name));
        return ok ? 1 : 0;
    }

}
