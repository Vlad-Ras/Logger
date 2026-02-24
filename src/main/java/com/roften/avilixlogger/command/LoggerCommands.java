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
            .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.use", 2))
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
                    .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane", 2))
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
                    .executes(ctx -> lookupFromFlags(ctx, StringArgumentType.getString(ctx, "args"))))
    );

    // Aliases for plane ownership tools:
    // /owner info|set|reset  ->  /log plane owner info|set|reset
    d.register(literal("owner")
            .requires(s -> com.roften.avilixlogger.core.PermissionUtil.has(s, "avilixlogger.command.plane", 2))
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
            if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logEntities.get()) return;

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

    private static void probeNextCursor(ServerLevel level, ServerPlayer sp) {
        LastQueryManager.State st = LastQueryManager.get(sp);
        if (st == null) return;

        int size = ChatLogPager.pageSize();

        LogQuery q = st.baseQuery.copy();
        q.beforeId = st.currentBeforeId();
        q.limit = size + 1;

        List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(q);
        if (raw == null) raw = List.of();

        boolean hasNext = raw.size() > size;
        List<LogEntry> page = raw;
        if (hasNext) page = raw.subList(0, size);

        long nextCursorCandidate = 0L;
        if (!page.isEmpty()) nextCursorCandidate = page.get(page.size() - 1).id;

        st.nextCursorCandidate = nextCursorCandidate;
        st.hasNext = hasNext;
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

        // Cursor-based pagination: to reach page N, we step forward from the first page.
        LastQueryManager.first(sp);

        for (int page = 1; page < target; page++) {
            probeNextCursor(level, sp);
            st = LastQueryManager.get(sp);
            if (st == null) break;
            if (!st.hasNext() || st.nextCursorCandidate() <= 0) break;
            LastQueryManager.next(sp, st.nextCursorCandidate());
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
        q.dim = level.dimension().location().toString();
        q.sinceTs = System.currentTimeMillis() - (seconds * 1000L);
        q.untilTs = System.currentTimeMillis();
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;
        if (radius <= 0) {
            q.exactPos = center;
        } else {
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
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

        // Refresh cursors (so NEXT availability is correct).
        probeNextCursor(lvl, sp);

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
        q.dim = level.dimension().location().toString();
        q.sinceTs = range[0];
        q.untilTs = range[1];
        q.limit = LoggerConfig.VALUES.lookupDefaultLimit.get();
        if (actor != null && !actor.isBlank()) q.actorName = actor;
        if (radius <= 0) {
            q.exactPos = center;
        } else {
            q.minPos = center.offset(-radius, -radius, -radius);
            q.maxPos = center.offset(radius, radius, radius);
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
        q.dim = null; // all dimensions
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
        src.sendSystemMessage(Component.literal("  /log rollback --t 2h --r 30 --m grief   (предпросмотр)" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  /log rollback --t 2h --r 30 --m grief --c   (применить)" ).withStyle(ChatFormatting.GRAY));

        src.sendSystemMessage(Component.literal("Флаги (коротко)").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  --t time | --d date | --r radius | --m mode | --p player | --di dim | --ad all-dims | --c confirm" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  mode: grief | theft | combat | planes | all   |   types: --ty break,place,container_take" ).withStyle(ChatFormatting.GRAY));
        src.sendSystemMessage(Component.literal("  dim: overworld | nether | end   (или полный id: minecraft:overworld)" ).withStyle(ChatFormatting.GRAY));

        src.sendSystemMessage(Component.literal("Пресеты").withStyle(ChatFormatting.YELLOW));
        src.sendSystemMessage(Component.literal("  /log preset save <name> <args...>   |   /log preset run <name> [args...]" ).withStyle(ChatFormatting.GRAY));
    }

    private static final class ParsedFlags {
        Integer seconds;           // from --time
        long[] dayRange;           // from --date/--day
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
        boolean weSelection;       // --we (use WorldEdit selection)
        boolean targetBlock;       // --block (targeted block)

        ParsedFlags copy() {
            ParsedFlags p = new ParsedFlags();
            p.seconds = this.seconds;
            p.dayRange = this.dayRange;
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
                case "world", "global", "w" -> {
                    out.world = true;
                    out.allDims = true;
                }
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
                case "we", "worldedit" -> out.weSelection = true;
                case "block", "target", "target-block" -> out.targetBlock = true;
                default -> errors.add("Неизвестный флаг: --" + key);
            }
        }

        // Sanity: disallow mixing absolute date range and relative time.
        if (out.seconds != null && out.dayRange != null) {
            errors.add("Нельзя использовать вместе --time и --date. Выберите одно.");
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
            case "theft", "steal" -> EnumSet.of(ActionType.CONTAINER_OPEN, ActionType.CONTAINER_PUT, ActionType.CONTAINER_TAKE, ActionType.ITEM_PICKUP, ActionType.ITEM_DROP);
            case "combat", "pvp" -> EnumSet.of(ActionType.ENTITY_DEATH, ActionType.PLAYER_DEATH, ActionType.ENTITY_SPAWN);
            case "planes", "plane", "aircraft" -> EnumSet.of(ActionType.PLANE_PLACE, ActionType.PLANE_REMOVE, ActionType.PLANE_MOUNT, ActionType.PLANE_PICKUP, ActionType.ENTITY_OWNER_SET);
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
        if (direct.seconds != null) merged.seconds = direct.seconds;
        if (direct.dayRange != null) merged.dayRange = direct.dayRange;
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
        merged.confirm = direct.confirm || base.confirm;
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

        LogQuery q = new LogQuery();
        if (f.allDims) q.dim = null;
        else if (f.dim != null && !f.dim.isBlank()) q.dim = f.dim;
        else q.dim = level.dimension().location().toString();

        if (f.dayRange != null) {
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

        if (!f.world) {
            BlockPos center = sp.blockPosition();
            if (radius <= 0) q.exactPos = center;
            else {
                q.minPos = center.offset(-radius, -radius, -radius);
                q.maxPos = center.offset(radius, radius, radius);
            }
        } else {
            // World/global search: no position bounding box.
            q.exactPos = null;
            q.minPos = null;
            q.maxPos = null;
        }

        String title;
        if (f.dayRange != null) {
            title = "Поиск дата=" + rawDateForTitle(f.dayRange) + ", r=" + radius;
        } else {
            title = "Поиск " + formatDuration(seconds) + ", r=" + radius;
        }
        if (f.mode != null && !f.mode.isBlank()) title += ", mode=" + f.mode;
        if (f.world) title += ", world";
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

    if (f.types == null) {
        EnumSet<ActionType> byMode = typesForMode(f.mode);
        if (byMode != null) f.types = byMode;
    }

    if (!errors.isEmpty()) {
        ctx.getSource().sendFailure(Component.literal(String.join("; ", errors)));
        sendShortHelp(ctx.getSource());
        return 0;
    }

    if (f.seconds == null && f.dayRange == null) {
        ctx.getSource().sendFailure(Component.literal("Для отката нужен --time или --date."));
        return 0;
    }

    if (ctx.getSource().getServer() == null) {
        ctx.getSource().sendFailure(Component.literal("Сервер недоступен."));
        return 0;
    }

    final boolean apply = f.confirm; // safe mode by default
    final int radius = f.radius != null ? f.radius : 5;
    final String actor = (f.actor != null && !f.actor.isBlank()) ? f.actor : null;

    RollbackReport total = new RollbackReport();

    // Scope shortcut: WorldEdit selection
    if (f.weSelection) {
        if (f.allDims) {
            ctx.getSource().sendFailure(Component.literal("--we нельзя использовать вместе с --all-dims."));
            return 0;
        }
        BlockPos[] sel = WorldEditIntegration.getSelection(sp);
        if (sel == null) {
            ctx.getSource().sendFailure(Component.literal("WorldEdit selection not found. Убедитесь, что WorldEdit установлен и у вас есть выделение."));
            return 0;
        }
        ServerLevel lvl = sp.serverLevel(); // selection is bound to player's current world
        RollbackReport r = (f.dayRange != null)
                ? (apply ? RollbackEngine.rollbackBoxRangeReport(lvl, sel[0], sel[1], f.dayRange[0], f.dayRange[1], actor, f.types)
                         : RollbackEngine.previewBoxRangeReport(lvl, sel[0], sel[1], f.dayRange[0], f.dayRange[1], actor, f.types))
                : (apply ? RollbackEngine.rollbackBoxReport(lvl, sel[0], sel[1], System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types)
                         : RollbackEngine.previewBoxReport(lvl, sel[0], sel[1], System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types));
        mergeReports(total, r);
    }
    // Scope shortcut: targeted block
    else if (f.targetBlock) {
        if (f.allDims) {
            ctx.getSource().sendFailure(Component.literal("--block нельзя использовать вместе с --all-dims."));
            return 0;
        }
        ServerLevel lvl = sp.serverLevel();
        BlockHitResult hit = RayTraceUtil.getPlayerPOVHitResult(sp, lvl, 6.0);
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.literal("Не выбран блок. Наведитесь на блок и повторите."));
            return 0;
        }
        BlockPos pos = hit.getBlockPos();
        BlockPos min = pos;
        BlockPos max = pos;

        // If chest: include both halves if double chest.
        BlockState state = lvl.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock) {
            BlockPos other = ChestUtil.getConnectedChestPos(lvl, pos, state);
            if (other != null) {
                min = new BlockPos(Math.min(pos.getX(), other.getX()), Math.min(pos.getY(), other.getY()), Math.min(pos.getZ(), other.getZ()));
                max = new BlockPos(Math.max(pos.getX(), other.getX()), Math.max(pos.getY(), other.getY()), Math.max(pos.getZ(), other.getZ()));
            }
        }

        RollbackReport r = (f.dayRange != null)
                ? (apply ? RollbackEngine.rollbackBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types)
                         : RollbackEngine.previewBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types))
                : (apply ? RollbackEngine.rollbackBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types)
                         : RollbackEngine.previewBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types));
        mergeReports(total, r);
    }
    // Normal scope: radius box around player (or all dims)
    else {
        if (f.allDims) {
            for (ServerLevel lvl : ctx.getSource().getServer().getAllLevels()) {
                BlockPos min = new BlockPos(-30_000_000, -2048, -30_000_000);
                BlockPos max = new BlockPos(30_000_000, 4096, 30_000_000);
                RollbackReport r = (f.dayRange != null)
                        ? (apply ? RollbackEngine.rollbackBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types)
                                 : RollbackEngine.previewBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types))
                        : (apply ? RollbackEngine.rollbackBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types)
                                 : RollbackEngine.previewBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types));
                mergeReports(total, r);
            }
        } else {
            ServerLevel lvl;
            if (f.dim != null && !f.dim.isBlank()) {
                lvl = resolveLevelForDim(ctx.getSource(), f.dim);
                if (lvl == null) lvl = sp.serverLevel();
            } else {
                lvl = sp.serverLevel();
            }
            BlockPos center = sp.blockPosition();
            BlockPos min = center.offset(-radius, -radius, -radius);
            BlockPos max = center.offset(radius, radius, radius);

            RollbackReport r = (f.dayRange != null)
                    ? (apply ? RollbackEngine.rollbackBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types)
                             : RollbackEngine.previewBoxRangeReport(lvl, min, max, f.dayRange[0], f.dayRange[1], actor, f.types))
                    : (apply ? RollbackEngine.rollbackBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types)
                             : RollbackEngine.previewBoxReport(lvl, min, max, System.currentTimeMillis() - (f.seconds * 1000L), actor, f.types));
            mergeReports(total, r);
        }
    }

    if (apply) {
        sendRollbackSummary(ctx.getSource(), true, total);
    } else {
        sendRollbackSummary(ctx.getSource(), false, total);
        ctx.getSource().sendSystemMessage(Component.literal("Применить: повторите команду с флагом --c (или --confirm)." ).withStyle(ChatFormatting.GRAY));
    }
    return 1;
}


    private static void mergeReports(RollbackReport into, RollbackReport add) {
        if (into == null || add == null) return;
        into.processed += add.processed;
        into.applied += add.applied;
        into.skipped += add.skipped;
        into.blocksRestored += add.blocksRestored;
        into.blockEntitiesRestored += add.blockEntitiesRestored;
        into.entitiesRespawned += add.entitiesRespawned;
        into.entitiesRemoved += add.entitiesRemoved;
        into.itemsGivenOrSpawned += add.itemsGivenOrSpawned;
        into.itemsRemovedFromInventory += add.itemsRemovedFromInventory;
        for (var e : add.appliedByType.entrySet()) into.appliedByType.merge(e.getKey(), e.getValue(), Integer::sum);
        for (var e : add.skippedReasons.entrySet()) into.skippedReasons.merge(e.getKey(), e.getValue(), Integer::sum);
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
        if (f.allDims) q.dim = null;
        else if (f.dim != null && !f.dim.isBlank()) q.dim = f.dim;
        if (f.actor != null && !f.actor.isBlank()) q.actorName = f.actor;
        if (f.limit != null) q.limit = Math.max(1, Math.min(200, f.limit));

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

        if (f.radius != null) {
            int radius = f.radius;
            BlockPos center = sp.blockPosition();
            if (radius <= 0) {
                q.exactPos = center;
                q.minPos = null;
                q.maxPos = null;
            } else {
                q.exactPos = null;
                q.minPos = center.offset(-radius, -radius, -radius);
                q.maxPos = center.offset(radius, radius, radius);
            }
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