package com.roften.avilixlogger.net;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.core.PermissionUtil;
import com.roften.avilixlogger.core.ServerTickScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClientReportNetwork {
    private static final String ADMIN_PERMISSION = "avilixlogger.command.admin";
    private static final int TIMEOUT_TICKS = 200;
    private static final int COOLDOWN_TICKS = 60;
    private static final int CHAT_CHUNK_SIZE = 48;
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Snapshot> LATEST = new HashMap<>();
    private static final Map<UUID, Long> NEXT_REQUEST = new HashMap<>();
    private static long lastId;

    private record Pending(long id, ServerGamePacketListenerImpl connection, CommandSourceStack requester) {}
    private record Snapshot(Instant receivedAt, ClientReportPayloads.Report report) {}

    private ClientReportNetwork() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("client-report")
                .requires(ClientReportNetwork::canRequest)
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> request(EntityArgument.getPlayer(ctx, "player"), ctx.getSource()))
                        .then(Commands.literal("cached")
                                .executes(ctx -> cached(EntityArgument.getPlayer(ctx, "player"), ctx.getSource()))));
    }

    public static void onLogin(ServerPlayer target) {
        if (LoggerConfig.VALUES.clientReportOnJoin.get() && LoggerConfig.isEnabled()
                && AuthCoreBridge.isAuthorized(target.getServer())) {
            request(target, null);
        }
    }

    static void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Pending pending = PENDING.remove(uuid);
        LATEST.remove(uuid);
        NEXT_REQUEST.remove(uuid);
        if (pending != null) {
            notifyRequester(pending.requester(), "[AvilixLogger] " + name(player) + " вышел до получения отчёта.");
        }
    }

    public static void clear() {
        PENDING.clear();
        LATEST.clear();
        NEXT_REQUEST.clear();
    }

    static void receive(ClientReportPayloads.Report report, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID uuid = player.getUUID();
            Pending pending = PENDING.get(uuid);
            if (pending == null || pending.id() != report.id() || pending.connection() != player.connection) return;
            PENDING.remove(uuid);
            if (!LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(player.getServer())) return;
            if (pending.requester() != null && !canDeliver(pending.requester())) return;
            Snapshot snapshot = new Snapshot(Instant.now(), report);
            LATEST.put(uuid, snapshot);
            if (pending.requester() != null) show(pending.requester(), player, snapshot);
        });
    }

    private static boolean canRequest(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer) {
            return PermissionUtil.has(source, ADMIN_PERMISSION, 2);
        }
        return source.getEntity() == null && source.hasPermission(4)
                && AuthCoreBridge.isAuthorized(source.getServer());
    }

    private static boolean canDeliver(CommandSourceStack source) {
        if (!canRequest(source)) return false;
        if (!(source.getEntity() instanceof ServerPlayer player)) return true;
        ServerPlayer live = source.getServer().getPlayerList().getPlayer(player.getUUID());
        return live != null && live.connection == player.connection && canRequest(live.createCommandSourceStack());
    }

    private static int request(ServerPlayer target, CommandSourceStack requester) {
        if (!LoggerConfig.isEnabled()) return fail(requester, "логгер выключен.");
        if (!target.connection.hasChannel(ClientReportPayloads.Request.TYPE)
                || !target.connection.hasChannel(ClientReportPayloads.Report.TYPE)) {
            return fail(requester, "клиент " + name(target) + " не поддерживает отчёты.");
        }
        UUID uuid = target.getUUID();
        if (PENDING.containsKey(uuid)) {
            return fail(requester, "для " + name(target) + " уже ожидается отчёт.");
        }
        var server = target.getServer();
        long tick = server.getTickCount();
        if (tick < NEXT_REQUEST.getOrDefault(uuid, 0L)) {
            return fail(requester, "повторите запрос через несколько секунд.");
        }

        Pending pending = new Pending(++lastId, target.connection, requester);
        PENDING.put(uuid, pending);
        NEXT_REQUEST.put(uuid, tick + COOLDOWN_TICKS);
        PacketDistributor.sendToPlayer(target, new ClientReportPayloads.Request(pending.id()));
        if (requester != null) {
            requester.sendSystemMessage(Component.literal("[AvilixLogger] Запрос отправлен игроку " + name(target) + "."));
        }
        String targetName = name(target);
        ServerTickScheduler.schedule(server, TIMEOUT_TICKS, () -> {
            if (PENDING.remove(uuid, pending)) {
                notifyRequester(requester, "[AvilixLogger] " + targetName + " не прислал отчёт за отведённое время.");
            }
        });
        return 1;
    }

    private static int cached(ServerPlayer target, CommandSourceStack requester) {
        if (!LoggerConfig.isEnabled()) return fail(requester, "логгер выключен.");
        Snapshot snapshot = LATEST.get(target.getUUID());
        if (snapshot == null) return fail(requester, "в этой сессии ещё нет отчёта игрока " + name(target) + ".");
        show(requester, target, snapshot);
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        if (source != null) source.sendFailure(Component.literal("[AvilixLogger] " + message));
        return 0;
    }

    private static void notifyRequester(CommandSourceStack source, String message) {
        if (source != null && canDeliver(source)) source.sendSystemMessage(Component.literal(message));
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    private static void show(CommandSourceStack source, ServerPlayer target, Snapshot snapshot) {
        var report = snapshot.report();
        String time = java.time.format.DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX")
                .withZone(java.time.ZoneId.systemDefault()).format(snapshot.receivedAt());
        source.sendSystemMessage(Component.literal("[AvilixLogger] Отчёт " + name(target) + " · " + time
                + " · модов " + report.mods().size() + " · ресурс-паков " + report.packs().size()
                + (report.truncated() ? " · список сокращён по лимиту" : "")
                + ". Данные сообщены клиентом."));
        showList(source, "Моды", report.mods().stream().map(mod -> mod.id() + " " + mod.version()).toList());
        showList(source, "Ресурс-паки", report.packs());
    }

    private static void showList(CommandSourceStack source, String title, List<String> entries) {
        if (entries.isEmpty()) return;
        boolean inGame = source.getEntity() instanceof ServerPlayer;
        for (int start = 0; start < entries.size(); start += CHAT_CHUNK_SIZE) {
            int end = Math.min(start + CHAT_CHUNK_SIZE, entries.size());
            String text = String.join("\n", entries.subList(start, end));
            String label = title + " (" + (start + 1) + "–" + end + ")";
            Component line = inGame
                    ? Component.literal("[" + label + " · копировать]").withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text)))
                    : Component.literal(label + "\n" + text);
            source.sendSystemMessage(line);
        }
    }
}
