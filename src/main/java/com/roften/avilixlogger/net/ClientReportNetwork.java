package com.roften.avilixlogger.net;

import com.mojang.brigadier.arguments.StringArgumentType;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ClientReportNetwork {
    private static final String ADMIN_PERMISSION = "avilixlogger.command.admin";
    private static final int TIMEOUT_TICKS = 200;
    private static final int COOLDOWN_TICKS = 60;
    private static final int CHAT_CHUNK_SIZE = 48;
    private static final int GUI_CHUNK_SIZE = 32;
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX");
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final ClientReportCache LATEST = new ClientReportCache(LoggerNetwork.reportExpiryExecutor(), System::nanoTime);
    private static final Map<UUID, Long> NEXT_REQUEST = new HashMap<>();
    private static long lastId;
    private static Boolean enabledOverride;

    private record Pending(long id, ServerGamePacketListenerImpl connection, CommandSourceStack requester) {}

    private ClientReportNetwork() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("client-report")
                .requires(ClientReportNetwork::canRequest)
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(ctx -> request(EntityArgument.getPlayer(ctx, "player"), ctx.getSource()))
                        .then(Commands.literal("cached")
                                .executes(ctx -> cached(EntityArgument.getPlayer(ctx, "player"), ctx.getSource()))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> cacheCommand() {
        return Commands.literal("client-report-cache")
                .requires(ClientReportNetwork::canRequest)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> cached(StringArgumentType.getString(ctx, "name"), ctx.getSource())));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> settingsCommand() {
        return Commands.literal("client-report-settings")
                .requires(ClientReportNetwork::canRequest)
                .executes(ctx -> reportSettings(ctx.getSource()))
                .then(Commands.literal("on").executes(ctx -> setEnabled(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setEnabled(ctx.getSource(), false)));
    }

    private static int setEnabled(CommandSourceStack source, boolean value) {
        enabledOverride = value;
        if (!value) clearReports();
        return reportSettings(source);
    }

    private static int reportSettings(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal("[AvilixLogger] Отчёты о клиентах "
                + (enabled() ? "включены" : "выключены") + (enabledOverride == null ? "." : " до перезапуска сервера.")));
        return 1;
    }

    private static boolean enabled() {
        return enabledOverride != null ? enabledOverride : LoggerConfig.VALUES.clientAuditEnabled.get();
    }

    static void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        Pending pending = PENDING.remove(uuid);
        NEXT_REQUEST.remove(uuid);
        if (pending != null) {
            notifyRequester(pending.requester(), "[AvilixLogger] " + name(player) + " вышел до получения отчёта.");
        }
    }

    public static void clear() {
        enabledOverride = null;
        clearReports();
    }

    private static void clearReports() {
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
            if (!enabled() || !LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(player.getServer())) return;
            if (!canDeliver(pending.requester())) return;
            var snapshot = LATEST.store(uuid, name(player), report);
            writeReport(pending.requester(), snapshot.playerName(), snapshot.receivedAt(), snapshot.report());
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
        if (!(source.getEntity() instanceof ServerPlayer player)) return canRequest(source);
        ServerPlayer live = source.getServer().getPlayerList().getPlayer(player.getUUID());
        return live != null && live.connection == player.connection && canRequest(live.createCommandSourceStack());
    }

    private static int request(ServerPlayer target, CommandSourceStack requester) {
        if (!enabled()) return fail(requester, "отчёты о клиентах выключены.");
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

        long id = ++lastId;
        PENDING.put(uuid, new Pending(id, target.connection, requester));
        NEXT_REQUEST.put(uuid, tick + COOLDOWN_TICKS);
        PacketDistributor.sendToPlayer(target, new ClientReportPayloads.Request(id));
        requester.sendSystemMessage(Component.literal("[AvilixLogger] Запрос отправлен игроку " + name(target) + "."));
        String targetName = name(target);
        ServerTickScheduler.schedule(server, TIMEOUT_TICKS, () -> {
            Pending expired = PENDING.get(uuid);
            if (expired != null && expired.id() == id) {
                PENDING.remove(uuid);
                notifyRequester(expired.requester(), "[AvilixLogger] " + targetName + " не прислал отчёт за отведённое время.");
            }
        });
        return 1;
    }

    private static int cached(ServerPlayer target, CommandSourceStack requester) {
        return showCached(requester, name(target), LATEST.find(target.getUUID()));
    }

    private static int cached(String playerName, CommandSourceStack requester) {
        var online = requester.getServer().getPlayerList().getPlayerByName(playerName);
        if (online != null) return cached(online, requester);
        return showCached(requester, playerName, LATEST.find(playerName));
    }

    private static int showCached(CommandSourceStack requester, String playerName, ClientReportCache.Snapshot snapshot) {
        if (!enabled()) return fail(requester, "отчёты о клиентах выключены.");
        if (!LoggerConfig.isEnabled()) return fail(requester, "логгер выключен.");
        if (snapshot == null) return fail(requester, "нет сохранённого отчёта игрока " + playerName + " за последние 24 часа.");
        writeReport(requester, snapshot.playerName(), snapshot.receivedAt(), snapshot.report());
        return 1;
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(Component.literal("[AvilixLogger] " + message));
        return 0;
    }

    private static void notifyRequester(CommandSourceStack source, String message) {
        if (canDeliver(source)) source.sendSystemMessage(Component.literal(message));
    }

    private static String name(ServerPlayer player) {
        return player.getGameProfile().getName();
    }

    static void writeReport(CommandSourceStack source, String playerName, Instant receivedAt, ClientReportPayloads.Report report) {
        boolean gui = source instanceof GuiCommandOutput.Capture capture && capture.avilixlogger$capturesGuiOutput();
        if (!gui && !(source.getEntity() instanceof ServerPlayer)) {
            // Dedicated server console replies become persistent Minecraft log entries.
            source.sendSystemMessage(Component.literal("[AvilixLogger] Отчёт доступен. Полный список можно открыть в игре."));
            return;
        }
        String time = REPORT_TIME.format(receivedAt.atZone(ZoneId.systemDefault()));
        source.sendSystemMessage(Component.literal("[AvilixLogger] Отчёт " + playerName + " · " + time
                + " · модов " + report.mods().size() + " · ресурс-паков " + report.packs().size()
                + (report.truncated() ? " · список сокращён по лимиту" : "")
                + ". Данные сообщены клиентом."));
        showList(source, "Моды", report.mods().stream().map(mod -> mod.id() + " " + mod.version()).toList(), gui);
        showList(source, "Ресурс-паки", report.packs(), gui);
    }

    private static void showList(CommandSourceStack source, String title, List<String> entries, boolean gui) {
        if (entries.isEmpty()) return;
        int chunkSize = gui ? GUI_CHUNK_SIZE : CHAT_CHUNK_SIZE;
        for (int start = 0; start < entries.size(); start += chunkSize) {
            int end = Math.min(start + chunkSize, entries.size());
            String text = String.join("\n", entries.subList(start, end));
            String label = title + " (" + (start + 1) + "–" + end + ")";
            Component line = !gui
                    ? Component.literal("[" + label + " · копировать]").withStyle(style -> style
                            .withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, text)))
                    : Component.literal(label + "\n" + text);
            source.sendSystemMessage(line);
        }
    }
}
