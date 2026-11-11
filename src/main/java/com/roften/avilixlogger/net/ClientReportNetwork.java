package com.roften.avilixlogger.net;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.ClickHouseLogStorage;
import com.roften.avilixlogger.core.LogQuery;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.core.LoggerServerData;
import com.roften.avilixlogger.core.PermissionUtil;
import com.roften.avilixlogger.core.ServerTickScheduler;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.UUID;

public final class ClientReportNetwork {
    private static final String ADMIN_PERMISSION = "avilixlogger.command.admin";
    private static final int TIMEOUT_TICKS = 200;
    private static final long MANUAL_COOLDOWN_NANOS = Duration.ofSeconds(10).toNanos();
    private static final int CHAT_CHUNK_SIZE = 48;
    private static final int GUI_CHUNK_SIZE = 32;
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX");
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Long> LAST_MANUAL_REQUEST = new HashMap<>();
    private static final Map<UUID, Object> COMMAND_QUERIES = new HashMap<>();
    private static final Pattern PACK_SEPARATORS = Pattern.compile("[_\\s-]+");
    private static long lastId;
    private static Boolean enabledOverride;
    private static int automaticCursor;
    private static long reportGeneration;

    private record Pending(long id, ServerGamePacketListenerImpl connection, Reply reply) {}

    private record Reply(CommandSourceStack source, Long requestId) {
        void status(String text, boolean failure) {
            if (requestId != null) sendView(source, ClientReportViewPayloads.View.message(requestId, text));
            else if (failure) source.sendFailure(Component.literal("[AvilixLogger] " + text));
            else source.sendSystemMessage(Component.literal("[AvilixLogger] " + text));
        }

        void report(ClickHouseLogStorage.ClientReport snapshot) {
            if (requestId == null) {
                writeReport(source, snapshot.playerName(), snapshot.receivedAt(), snapshot.report());
                return;
            }
            sendView(source, new ClientReportViewPayloads.View(requestId, "", snapshot.playerName(),
                    snapshot.receivedAt().toEpochMilli(), List.of(), findMatches(snapshot.report()), snapshot.report()));
        }
    }

    private ClientReportNetwork() {}

    public static LiteralArgumentBuilder<CommandSourceStack> command() {
        return Commands.literal("client-report")
                .requires(ClientReportNetwork::canRequest)
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> request(StringArgumentType.getString(ctx, "player"), new Reply(ctx.getSource(), null)))
                        .then(Commands.literal("cached")
                                .executes(ctx -> cached(StringArgumentType.getString(ctx, "player"), new Reply(ctx.getSource(), null)))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> cacheCommand() {
        return Commands.literal("client-report-cache")
                .requires(ClientReportNetwork::canRequest)
                .then(Commands.argument("name", StringArgumentType.word())
                        .executes(ctx -> cached(StringArgumentType.getString(ctx, "name"), new Reply(ctx.getSource(), null))));
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

    public static void onLogin(ServerPlayer player) {
        if (enabled() && LoggerConfig.isEnabled()) requestOnline(player, null);
    }

    /** Check one connected client each second to spread daily refreshes across server ticks. */
    public static void checkConnectedPlayer(MinecraftServer server) {
        if (!enabled() || !LoggerConfig.isEnabled()) return;
        var players = server.getPlayerList().getPlayers();
        if (!players.isEmpty()) {
            var player = players.get(Math.floorMod(automaticCursor++, players.size()));
            requestOnline(player, null);
        }
    }

    static void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        var pending = PENDING.remove(uuid);
        COMMAND_QUERIES.remove(uuid);
        LAST_MANUAL_REQUEST.remove(uuid);
        if (pending != null) notifyRequester(pending.reply(), name(player) + " вышел до получения отчёта.");
    }

    public static void clear() {
        enabledOverride = null;
        automaticCursor = 0;
        LAST_MANUAL_REQUEST.clear();
        clearReports();
    }

    private static void clearReports() {
        reportGeneration++;
        PENDING.clear();
        COMMAND_QUERIES.clear();
    }

    static void receive(ClientReportPayloads.Report report, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID uuid = player.getUUID();
            Pending pending = PENDING.get(uuid);
            if (pending == null || pending.id() != report.id() || pending.connection() != player.connection) return;
            PENDING.remove(uuid);
            if (!enabled() || !LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(player.getServer())) return;
            saveReport(player, pending.reply(), new ClickHouseLogStorage.ClientReport(name(player), Instant.now(), report));
        });
    }

    private static void saveReport(ServerPlayer player, Reply reply, ClickHouseLogStorage.ClientReport snapshot) {
        var storage = LoggerRuntime.clientReportStorage();
        if (storage == null) {
            notifyRequester(reply, "Сохранение отчёта не подтверждено: ClickHouse ещё недоступен.");
            return;
        }
        var server = player.getServer();
        var playerId = player.getUUID();
        long generation = reportGeneration;
        try {
            LoggerNetwork.executeClientReportIo(() -> {
                boolean saved;
                try {
                    storeWithRetry(storage, playerId, snapshot);
                    saved = true;
                } catch (IOException | RuntimeException failure) {
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Client report write not confirmed ({})",
                            failure.getClass().getSimpleName());
                    saved = false;
                }
                boolean acknowledged = saved;
                server.execute(() -> {
                    if (generation != reportGeneration || !enabled() || !LoggerConfig.isEnabled()) return;
                    if (!acknowledged) {
                        notifyRequester(reply, "ClickHouse не подтвердил сохранение нового отчёта.");
                        return;
                    }
                    if (reply != null && canDeliver(reply.source())) reply.report(snapshot);
                    notifyMatches(server, playerId, snapshot.playerName(), findMatches(snapshot.report()));
                });
            });
        } catch (RejectedExecutionException busy) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Client report I/O queue is full; a report was not saved.");
            notifyRequester(reply, "Очередь сохранения отчётов заполнена. Повторите ручное обновление позже.");
        }
    }

    private static void storeWithRetry(ClickHouseLogStorage storage, UUID playerId,
                                       ClickHouseLogStorage.ClientReport snapshot) throws IOException {
        try {
            storage.storeClientReport(playerId, snapshot);
        } catch (IOException failure) {
            if (Thread.currentThread().isInterrupted()) throw failure;
            // The same UUID and timestamp let ClickHouse merge duplicates if the first ACK was lost.
            storage.storeClientReport(playerId, snapshot);
        }
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

    static void handleView(ClientReportViewPayloads.Query query, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player) || !LoggerNetwork.isClientPresent(player)) return;
            var source = player.createCommandSourceStack();
            if (!canRequest(source) || !player.connection.hasChannel(ClientReportViewPayloads.View.TYPE)) return;
            var reply = new Reply(source, query.id());
            switch (query.action()) {
                case REQUEST -> request(query.name(), reply);
                case CACHED -> cached(query.name(), reply);
                case COMMANDS -> commands(player, query.name(), reply);
            }
        });
    }

    private static void sendView(CommandSourceStack source, ClientReportViewPayloads.View view) {
        if (source.getEntity() instanceof ServerPlayer player) PacketDistributor.sendToPlayer(player, view);
    }

    private static int request(String playerName, Reply reply) {
        if (!ready(playerName, reply)) return 0;
        var target = reply.source().getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) return feedback(reply, playerName + " офлайн. Откройте последний сохранённый отчёт.", true);
        return requestOnline(target, reply);
    }

    private static int requestOnline(ServerPlayer target, Reply reply) {
        if (!target.connection.hasChannel(ClientReportPayloads.Request.TYPE)
                || !target.connection.hasChannel(ClientReportPayloads.Report.TYPE)) {
            return feedback(reply, "Клиент " + name(target) + " не поддерживает отчёты.", true);
        }
        UUID uuid = target.getUUID();
        var existing = PENDING.get(uuid);
        if (existing != null) {
            if (reply != null && existing.reply() == null) {
                PENDING.put(uuid, new Pending(existing.id(), existing.connection(), reply));
                return feedback(reply, "Отчёт уже запрашивается. Ответ появится здесь.", false);
            }
            return feedback(reply, "Для " + name(target) + " уже ожидается отчёт.", true);
        }

        long nowNanos = System.nanoTime();
        if (reply != null) {
            Long last = LAST_MANUAL_REQUEST.get(uuid);
            if (last != null && nowNanos - last < MANUAL_COOLDOWN_NANOS) {
                return feedback(reply, "Повторите ручное обновление через несколько секунд.", true);
            }
        }
        var server = target.getServer();
        if (LoggerRuntime.clientReportStorage() == null) return feedback(reply, "ClickHouse ещё недоступен. Повторите запрос позже.", true);
        long nextAuto = LoggerServerData.get(server.overworld()).reserveClientReport(uuid, System.currentTimeMillis());
        if (reply == null && nextAuto != 0) return 0;

        long id = ++lastId;
        PENDING.put(uuid, new Pending(id, target.connection, reply));
        if (reply != null) LAST_MANUAL_REQUEST.put(uuid, nowNanos);
        PacketDistributor.sendToPlayer(target, new ClientReportPayloads.Request(id));
        feedback(reply, "Запрос отправлен игроку " + name(target) + ".", false);
        String targetName = name(target);
        ServerTickScheduler.schedule(server, TIMEOUT_TICKS, () -> {
            Pending expired = PENDING.get(uuid);
            if (expired != null && expired.id() == id) {
                PENDING.remove(uuid);
                notifyRequester(expired.reply(), targetName + " не прислал отчёт за отведённое время.");
            }
        });
        return 1;
    }

    private static int cached(String playerName, Reply reply) {
        if (!ready(playerName, reply)) return 0;
        var storage = LoggerRuntime.clientReportStorage();
        if (storage == null) return feedback(reply, "ClickHouse ещё недоступен. Повторите запрос позже.", true);
        var server = reply.source().getServer();
        var online = server.getPlayerList().getPlayerByName(playerName);
        UUID playerId = online == null ? null : online.getUUID();
        long generation = reportGeneration;
        try {
            LoggerNetwork.executeClientReportIo(() -> {
                try {
                    var snapshot = storage.findClientReport(playerId, playerName);
                    server.execute(() -> {
                        if (generation != reportGeneration || !enabled() || !LoggerConfig.isEnabled()
                                || !canDeliver(reply.source())) return;
                        if (snapshot == null || !snapshot.receivedAt().plus(Duration.ofHours(24)).isAfter(Instant.now())) {
                            feedback(reply, "Нет сохранённого отчёта игрока " + playerName + " за последние 24 часа.", true);
                        } else reply.report(snapshot);
                    });
                } catch (IOException | RuntimeException failure) {
                    server.execute(() -> {
                        if (generation == reportGeneration && enabled() && LoggerConfig.isEnabled()) {
                            notifyRequester(reply, "Отчёт не удалось прочитать из ClickHouse. Повторите запрос позже.");
                        }
                    });
                }
            });
            return feedback(reply, "Загружаю сохранённый отчёт из ClickHouse…", false);
        } catch (RejectedExecutionException busy) {
            return feedback(reply, "Очередь отчётов занята. Повторите запрос позже.", true);
        }
    }

    private static boolean ready(String playerName, Reply reply) {
        if (!ClientReportViewPayloads.validName(playerName)) {
            feedback(reply, "Введите ник Minecraft: 1–16 латинских букв, цифр или подчёркиваний.", true);
            return false;
        }
        if (!enabled() || !LoggerConfig.isEnabled()) {
            feedback(reply, "Отчёты о клиентах выключены.", true);
            return false;
        }
        return true;
    }

    private static int feedback(Reply reply, String message, boolean failure) {
        if (reply != null) reply.status(message, failure);
        return failure ? 0 : 1;
    }

    private static void notifyRequester(Reply reply, String message) {
        if (reply != null && canDeliver(reply.source())) reply.status(message, false);
    }

    private static void commands(ServerPlayer viewer, String name, Reply reply) {
        if (!ready(name, reply)) return;
        var id = viewer.getUUID();
        var token = new Object();
        if (COMMAND_QUERIES.putIfAbsent(id, token) != null) {
            feedback(reply, "Предыдущий запрос команд ещё выполняется.", true);
            return;
        }
        var server = viewer.getServer();
        var level = server.overworld();
        var query = new LogQuery();
        query.actorName = name;
        query.type = ActionType.CHAT_MESSAGE;
        query.extraTextFilter = "[Команда]";
        query.sinceTs = System.currentTimeMillis() - Duration.ofHours(24).toMillis();
        query.limit = ClientReportViewPayloads.MAX_COMMANDS;
        query.debugSource = "client-report-commands";
        try {
            LoggerNetwork.executeClientReportQuery(() -> {
                List<String> rows;
                String status;
                try {
                    rows = LoggerRuntime.storage(level).queryReverse(query).stream()
                            .filter(entry -> entry.extra != null && entry.extra.startsWith("[Команда]"))
                            .map(entry -> clip(REPORT_TIME.format(Instant.ofEpochMilli(entry.ts).atZone(ZoneId.systemDefault()))
                                    + " · " + entry.extra.substring("[Команда]".length()).stripLeading()))
                            .toList();
                    status = rows.isEmpty() ? "За 24 часа записанных серверных команд нет." : "";
                } catch (RuntimeException ex) {
                    rows = List.of();
                    status = "Журнал команд сейчас недоступен.";
                }
                var view = new ClientReportViewPayloads.View(reply.requestId(), status, name, 0, rows, List.of(), null);
                server.execute(() -> {
                    if (COMMAND_QUERIES.remove(id, token) && enabled() && canDeliver(reply.source())) sendView(reply.source(), view);
                });
            });
        } catch (RejectedExecutionException ex) {
            COMMAND_QUERIES.remove(id, token);
            feedback(reply, "Журнал занят. Повторите запрос позже.", true);
        }
    }

    private static String clip(String text) {
        int end = ClientReportViewPayloads.COMMAND_LENGTH - 1;
        if (text.length() <= end + 1) return text;
        if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end) + "…";
    }

    private static List<String> findMatches(ClientReportPayloads.Report report) {
        return matchPacks(report.packs(), LoggerConfig.VALUES.clientAuditSuspectPacks.get());
    }

    static List<String> matchPacks(List<String> packs, String configured) {
        if (packs.isEmpty() || configured.isBlank()) return List.of();
        var names = Arrays.stream(configured.split(",")).map(ClientReportNetwork::normalizePack)
                .filter(value -> !value.isEmpty()).toList();
        return packs.stream().filter(pack -> {
            String name = normalizePack(pack);
            return names.stream().anyMatch(watched -> matchesPack(name, watched));
        }).toList();
    }

    private static String normalizePack(String raw) {
        String name = raw.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("file/")) name = name.substring(5);
        if (name.endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return PACK_SEPARATORS.matcher(name).replaceAll(" ").strip();
    }

    private static boolean matchesPack(String name, String watched) {
        if (name.equals(watched)) return true;
        if (!name.startsWith(watched + " ")) return false;
        int version = watched.length() + 1;
        if (version < name.length() && name.charAt(version) == 'v') version++;
        return version < name.length() && Character.isDigit(name.charAt(version));
    }

    private static void notifyMatches(MinecraftServer server, UUID target, String targetName, List<String> matches) {
        if (matches.isEmpty()) return;
        var message = Component.literal("[AvilixLogger] " + targetName + " · совпадений по ресурс-пакам: "
                + matches.size() + ". Откройте отчёт для проверки.").withStyle(ChatFormatting.GOLD);
        for (var admin : server.getPlayerList().getPlayers()) {
            if (!admin.getUUID().equals(target) && admin.hasPermissions(2) && canRequest(admin.createCommandSourceStack())) {
                admin.sendSystemMessage(message);
            }
        }
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
