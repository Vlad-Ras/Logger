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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.UUID;

public final class ClientReportNetwork {
    private static final String ADMIN_PERMISSION = "avilixlogger.command.admin";
    // The first report may need to hash several gigabytes of mod archives.
    private static final int TIMEOUT_TICKS = 2400;
    private static final int SAVE_TIMEOUT_TICKS = 2400;
    private static final long AUTO_RETRY_MS = Duration.ofMinutes(2).toMillis();
    private static final long MANUAL_COOLDOWN_NANOS = Duration.ofSeconds(10).toNanos();
    private static final int CHAT_CHUNK_SIZE = 48;
    private static final int GUI_CHUNK_SIZE = 24;
    private static final DateTimeFormatter REPORT_TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX");
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final java.util.LinkedHashMap<UUID, HashCollection> HASH_COLLECTIONS = new java.util.LinkedHashMap<>();
    private static final long HASH_LIFETIME_NANOS = Duration.ofMinutes(8).toNanos();
    private static final int MAX_HASH_COLLECTIONS = 256;
    private static final class HashCollection {
        final Pending request;
        final ClickHouseLogStorage.ClientReport base;
        final ClientReportHashesPayload.Assembly assembly;
        final long started = System.nanoTime();
        Reply reply;
        Matches matches = new Matches(List.of(), List.of());
        ClickHouseLogStorage.ClientReport enriched;
        boolean baseFinished, baseAcknowledged, saving;
        HashCollection(Pending request, ClickHouseLogStorage.ClientReport base) {
            this.request = request; this.base = base; this.reply = request.reply();
            this.assembly = new ClientReportHashesPayload.Assembly(base.report());
        }
        ClickHouseLogStorage.ClientReport visible() { return enriched == null ? base : enriched; }
    }
    private static final Map<UUID, Long> LAST_MANUAL_REQUEST = new HashMap<>();
    private static final Map<UUID, Long> AUTO_RETRY_AT = new HashMap<>();
    private static final Map<UUID, Object> COMMAND_QUERIES = new HashMap<>();
    public static final String ALERT_PERMISSION = "avilixlogger.clientaudit.alerts";
    private static final java.util.ArrayDeque<AuditAlert> ALERTS = new java.util.ArrayDeque<>();
    private static final Pattern PACK_SEPARATORS = Pattern.compile("[_\\s-]+");
    private static long lastId;
    private static Boolean enabledOverride;
    private static int automaticCursor;
    private static long reportGeneration;

    private record AuditAlert(Instant time, String text) {}

    private record TriggerRules(String packs, List<String> mods, List<String> words) {
        static TriggerRules configured() {
            return new TriggerRules(LoggerConfig.VALUES.clientAuditSuspectPacks.get(),
                    splitRules(LoggerConfig.VALUES.clientAuditSuspectMods.get()),
                    splitRules(LoggerConfig.VALUES.clientAuditAlertWords.get()));
        }
    }

    private record Matches(List<String> packs, List<String> mods) {
        boolean empty() { return packs.isEmpty() && mods.isEmpty(); }
    }

    private record Pending(long id, ServerGamePacketListenerImpl connection, Reply reply, boolean received) {}

    private record Reply(CommandSourceStack source, Long requestId) {
        void status(String text, boolean failure) {
            if (requestId != null) sendView(source, ClientReportViewPayloads.View.message(requestId, text));
            else if (failure) source.sendFailure(Component.literal("[AvilixLogger] " + text));
            else source.sendSystemMessage(Component.literal("[AvilixLogger] " + text));
        }

        void report(ClickHouseLogStorage.ClientReport snapshot, Matches matches) { report(snapshot, matches, ""); }
        void report(ClickHouseLogStorage.ClientReport snapshot, Matches matches, String status) {
            if (requestId == null) {
                writeReport(source, snapshot.playerName(), snapshot.receivedAt(), snapshot.report());
                if (!status.isBlank()) status(status, false);
                return;
            }
            sendView(source, new ClientReportViewPayloads.View(requestId, status, snapshot.playerName(),
                    snapshot.receivedAt().toEpochMilli(), List.of(), matches.packs(), snapshot.report(), matches.mods()));
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

    public static LiteralArgumentBuilder<CommandSourceStack> alertsCommand() {
        return Commands.literal("client-report-alerts")
                .requires(source -> source.getEntity() instanceof ServerPlayer player && canReceiveAlerts(player))
                .then(Commands.literal("on").executes(ctx -> setAlerts(ctx.getSource(), true)))
                .then(Commands.literal("off").executes(ctx -> setAlerts(ctx.getSource(), false)))
                .executes(ctx -> {
                    var player = ctx.getSource().getPlayerOrException();
                    var data = LoggerServerData.get(player.getServer().overworld());
                    return showAlertSetting(ctx.getSource(), data.clientReportAlerts(player.getUUID()));
                });
    }

    private static int setAlerts(CommandSourceStack source, boolean value) {
        if (!(source.getEntity() instanceof ServerPlayer player)) return 0;
        LoggerServerData.get(player.getServer().overworld()).setClientReportAlerts(player.getUUID(), value);
        return showAlertSetting(source, value);
    }

    private static int showAlertSetting(CommandSourceStack source, boolean value) {
        source.sendSystemMessage(Component.literal("Личные уведомления об отчётах: " + (value ? "включены." : "выключены.")));
        return 1;
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

    /** Retry a bounded batch each second; do not make a join wait one second per connected client. */
    public static void checkConnectedPlayer(MinecraftServer server) {
        if (!enabled() || !LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(server)) {
            if (!PENDING.isEmpty() || !HASH_COLLECTIONS.isEmpty()) clearReports();
            return;
        }
        long now = System.nanoTime();
        var expired = HASH_COLLECTIONS.entrySet().iterator();
        while (expired.hasNext()) {
            var entry = expired.next(); var collection = entry.getValue();
            if (now - collection.started > HASH_LIFETIME_NANOS) {
                if (collection.reply != null && canDeliver(collection.reply.source())) collection.reply.report(
                        collection.visible(), collection.matches, "Список получен; вычисление SHA-256 не завершилось вовремя.");
                expired.remove();
            }
        }
        var players = server.getPlayerList().getPlayers();
        // Retry joins whose channel/storage handshake was not ready without waiting one second per online player.
        for (int i = 0; i < Math.min(8, players.size()); i++) {
            var player = players.get(Math.floorMod(automaticCursor++, players.size()));
            requestOnline(player, null);
        }
    }

    static void onLogout(ServerPlayer player) {
        UUID uuid = player.getUUID();
        var pending = PENDING.remove(uuid);
        HASH_COLLECTIONS.remove(uuid);
        COMMAND_QUERIES.remove(uuid);
        LAST_MANUAL_REQUEST.remove(uuid);
        AUTO_RETRY_AT.remove(uuid);
        if (pending != null) notifyRequester(pending.reply(), name(player) + " вышел до получения отчёта.");
    }

    public static void clear() {
        enabledOverride = null;
        automaticCursor = 0;
        LAST_MANUAL_REQUEST.clear();
        AUTO_RETRY_AT.clear();
        ALERTS.clear();
        clearReports();
    }

    private static void clearReports() {
        reportGeneration++;
        PENDING.clear();
        HASH_COLLECTIONS.clear();
        COMMAND_QUERIES.clear();
    }

    static void receiveStatus(ClientReportStatusPayloads.Status status, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID uuid = player.getUUID();
            Pending pending = PENDING.get(uuid);
            if (pending == null || pending.id() != status.id() || pending.connection() != player.connection
                    || pending.received()) return;
            if (status.stage() == ClientReportStatusPayloads.Stage.FAILED) {
                PENDING.remove(uuid);
                deferAutomaticRetry(uuid);
                notifyRequester(pending.reply(), name(player) + ": клиент не смог собрать отчёт.");
            } else if (pending.reply() != null && canDeliver(pending.reply().source())) {
                pending.reply().status("Клиент " + name(player) + " собирает сведения и SHA-256 в фоновом потоке…", false);
            }
        });
    }

    private static void deferAutomaticRetry(UUID uuid) {
        AUTO_RETRY_AT.put(uuid, System.currentTimeMillis() + AUTO_RETRY_MS);
    }

    static void receive(ClientReportPayloads.Report report, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            UUID uuid = player.getUUID();
            Pending pending = PENDING.get(uuid);
            if (pending == null || pending.received() || pending.id() != report.id()
                    || pending.connection() != player.connection) return;
            if (!enabled() || !LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(player.getServer())) {
                PENDING.remove(uuid);
                return;
            }
            // Keep the request in-flight until ClickHouse acknowledges it. Without this,
            // the once-a-second auto scanner can start duplicate collections during I/O.
            PENDING.put(uuid, new Pending(pending.id(), pending.connection(), pending.reply(), true));
            var snapshot = new ClickHouseLogStorage.ClientReport(name(player), Instant.now(), report);
            if (player.connection.hasChannel(ClientReportHashesPayload.TYPE)) {
                if (!HASH_COLLECTIONS.containsKey(uuid) && HASH_COLLECTIONS.size() >= MAX_HASH_COLLECTIONS)
                    HASH_COLLECTIONS.remove(HASH_COLLECTIONS.keySet().iterator().next());
                HASH_COLLECTIONS.put(uuid, new HashCollection(pending, snapshot));
            }
            if (pending.reply() != null && canDeliver(pending.reply().source())) pending.reply().report(snapshot,
                    new Matches(List.of(), List.of()), HASH_COLLECTIONS.containsKey(uuid)
                            ? "Список получен. Сохраняю отчёт; SHA-256 вычисляются отдельно…" : "Отчёт получен. Сохраняю в ClickHouse…");
            saveReport(player, pending, snapshot);
        });
    }

    private static void saveReport(ServerPlayer player, Pending request, ClickHouseLogStorage.ClientReport snapshot) {
        var storage = LoggerRuntime.clientReportStorage();
        var server = player.getServer();
        var playerId = player.getUUID();
        if (storage == null) {
            PENDING.remove(playerId);
            deferAutomaticRetry(playerId);
            notifyRequester(request.reply(), "Список получен, но сохранение не подтверждено: ClickHouse ещё недоступен.");
            finishBase(player, request, false, null);
            return;
        }

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
                Matches matches = acknowledged ? matchReport(snapshot.report(), TriggerRules.configured()) : new Matches(List.of(), List.of());
                server.execute(() -> {
                    if (generation != reportGeneration) return;
                    Pending current = PENDING.get(playerId);
                    if (current == null || current.id() != request.id() || current.connection() != request.connection()) return;
                    PENDING.remove(playerId);
                    if (!enabled() || !LoggerConfig.isEnabled()) return;
                    if (!acknowledged) {
                        deferAutomaticRetry(playerId);
                        notifyRequester(current.reply(), "Список получен, но ClickHouse не подтвердил сохранение нового отчёта.");
                        finishBase(player, request, false, matches);
                        return;
                    }
                    AUTO_RETRY_AT.remove(playerId);
                    LoggerServerData.get(server.overworld()).markClientReportSaved(playerId, System.currentTimeMillis());
                    var hashes = HASH_COLLECTIONS.get(playerId);
                    if (current.reply() != null && canDeliver(current.reply().source())) current.reply().report(
                            hashes == null ? snapshot : hashes.visible(), matches,
                            hashes == null ? "Отчёт сохранён." : "Список сохранён. SHA-256 дополняются отдельно…");
                    notifyMatches(server, snapshot.playerName(), matches);
                    finishBase(player, request, true, matches);
                });
            });
        } catch (RejectedExecutionException busy) {
            PENDING.remove(playerId);
            deferAutomaticRetry(playerId);
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Client report I/O queue is full; a report was not saved.");
            notifyRequester(request.reply(), "Список получен, но очередь сохранения заполнена. Повторите обновление позже.");
            finishBase(player, request, false, null);
        }
        ServerTickScheduler.schedule(server, SAVE_TIMEOUT_TICKS, () -> {
            Pending pending = PENDING.get(playerId);
            if (pending != null && pending.id() == request.id() && pending.received()) {
                PENDING.remove(playerId);
                deferAutomaticRetry(playerId);
                notifyRequester(pending.reply(), "Список получен, но ClickHouse слишком долго сохраняет отчёт.");
                finishBase(player, request, false, null);
            }
        });
    }

    private static void finishBase(ServerPlayer player, Pending request, boolean acknowledged, Matches matches) {
        var collection = HASH_COLLECTIONS.get(player.getUUID());
        if (collection == null || collection.request.id() != request.id() || collection.request.connection() != request.connection()) return;
        collection.baseFinished = true; collection.baseAcknowledged = acknowledged;
        if (matches != null) collection.matches = matches;
        if (collection.assembly.complete()) saveHashes(player, collection);
    }

    static void receiveHashes(ClientReportHashesPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            var collection = HASH_COLLECTIONS.get(player.getUUID());
            if (collection == null || collection.request.id() != payload.id() || collection.request.connection() != player.connection
                    || !enabled() || !LoggerConfig.isEnabled() || !AuthCoreBridge.isAuthorized(player.getServer())) return;
            try {
                if (!collection.assembly.accept(payload)) return;
            } catch (IllegalArgumentException invalid) {
                HASH_COLLECTIONS.remove(player.getUUID());
                notifyRequester(collection.reply, "Список получен; дополнение SHA-256 не соответствует этому запросу.");
                return;
            }
            Instant time = Instant.now();
            if (!time.isAfter(collection.base.receivedAt())) time = collection.base.receivedAt().plusMillis(1);
            // ClickHouse stores millisecond timestamps; ensure the enriched version sorts AFTER the base without FINAL.
            if (time.toEpochMilli() <= collection.base.receivedAt().toEpochMilli()) time = collection.base.receivedAt().plusMillis(1);
            collection.enriched = new ClickHouseLogStorage.ClientReport(collection.base.playerName(), time, collection.assembly.report());
            if (collection.reply != null && canDeliver(collection.reply.source())) collection.reply.report(collection.enriched, collection.matches,
                    collection.assembly.failed() ? "Список получен; SHA-256 сейчас недоступны."
                            : "Список и доступные SHA-256 получены. Сохраняю…");
            if (collection.baseFinished) saveHashes(player, collection);
        });
    }

    private static void saveHashes(ServerPlayer player, HashCollection collection) {
        if (collection.saving) return;
        UUID id = player.getUUID();
        if (collection.baseAcknowledged && (collection.assembly.failed()
                || collection.enriched.report().equals(collection.base.report()))) {
            HASH_COLLECTIONS.remove(id);
            if (collection.reply != null && canDeliver(collection.reply.source())) collection.reply.report(
                    collection.visible(), collection.matches, collection.assembly.failed()
                            ? "Список сохранён; SHA-256 сейчас недоступны." : "Отчёт сохранён.");
            return;
        }
        var storage = LoggerRuntime.clientReportStorage();
        if (storage == null) {
            HASH_COLLECTIONS.remove(id);
            notifyRequester(collection.reply, "Отчёт получен, но ClickHouse ещё недоступен для сохранения SHA-256."); return;
        }
        collection.saving = true;
        var server = player.getServer(); long generation = reportGeneration;
        try {
            LoggerNetwork.executeClientReportIo(() -> {
                boolean saved;
                try { storeWithRetry(storage, id, collection.enriched); saved = true; }
                catch (IOException | RuntimeException failure) {
                    saved = false;
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Client report hash write not confirmed ({})", failure.getClass().getSimpleName());
                }
                boolean acknowledged = saved;
                Matches matches = acknowledged ? matchReport(collection.enriched.report(), TriggerRules.configured()) : collection.matches;
                server.execute(() -> {
                    if (generation != reportGeneration || HASH_COLLECTIONS.get(id) != collection) return;
                    collection.matches = matches;
                    HASH_COLLECTIONS.remove(id);
                    if (!enabled() || !LoggerConfig.isEnabled()) return;
                    if (acknowledged) {
                        if (!collection.baseAcknowledged) {
                            AUTO_RETRY_AT.remove(id);
                            LoggerServerData.get(server.overworld()).markClientReportSaved(id, System.currentTimeMillis());
                            notifyMatches(server, collection.enriched.playerName(), collection.matches);
                        }
                        if (collection.reply != null && canDeliver(collection.reply.source())) collection.reply.report(
                                collection.enriched, collection.matches, collection.assembly.failed()
                                        ? "Список сохранён; SHA-256 сейчас недоступны." : "Отчёт с доступными SHA-256 сохранён.");
                    } else notifyRequester(collection.reply, "Список получен; сохранение SHA-256 в ClickHouse не подтверждено.");
                });
            });
        } catch (RejectedExecutionException busy) {
            HASH_COLLECTIONS.remove(id);
            notifyRequester(collection.reply, "Список получен; очередь сохранения SHA-256 занята.");
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
                case ACQUIRE -> acquire(query.name(), reply);
                case CACHED -> cached(query.name(), reply);
                case COMMANDS -> commands(player, query.name(), reply);
            }
        });
    }

    private static void sendView(CommandSourceStack source, ClientReportViewPayloads.View view) {
        if (source.getEntity() instanceof ServerPlayer player) {
            if (player.connection.hasChannel(ClientReportViewPayloads.FullView.TYPE)) {
                PacketDistributor.sendToPlayer(player, new ClientReportViewPayloads.FullView(view));
            } else {
                // Old admin clients retain their original v4 wire budget and explicit truncation.
                var report = view.report();
                if (report != null) report = ClientReportPayloads.collect(report.id(), report.mods(), report.packs(), report.packHashes())
                        .withTruncation(report.truncated());
                PacketDistributor.sendToPlayer(player, new ClientReportViewPayloads.View(view.id(), view.message(), view.player(),
                        view.receivedAt(), view.commands(), view.matches(), report, view.matchedModIds()));
            }
        }
    }

    private static int acquire(String playerName, Reply reply) {
        if (!ready(playerName, reply)) return 0;
        var server = reply.source().getServer();
        var target = server.getPlayerList().getPlayerByName(playerName);
        if (target == null) return cached(playerName, reply);
        if (PENDING.containsKey(target.getUUID()) || LoggerServerData.get(server.overworld())
                .nextClientReportAt(target.getUUID(), System.currentTimeMillis()) == 0) return requestOnline(target, reply);
        return cached(playerName, reply);
    }

    private static int request(String playerName, Reply reply) {
        if (!ready(playerName, reply)) return 0;
        var target = reply.source().getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) return feedback(reply, playerName + " офлайн. Откройте последний сохранённый отчёт.", true);
        return requestOnline(target, reply);
    }

    private static boolean sameRequester(Reply first, Reply second) {
        return first.source().getEntity() == second.source().getEntity();
    }

    private static int joinPending(ServerPlayer target, Pending existing, Reply reply) {
        UUID uuid = target.getUUID();
        if (reply != null && (existing.reply() == null || sameRequester(existing.reply(), reply) || !canDeliver(existing.reply().source()))) {
            PENDING.put(uuid, new Pending(existing.id(), existing.connection(), reply, existing.received()));
            var hashes = HASH_COLLECTIONS.get(uuid);
            if (hashes != null) {
                hashes.reply = reply;
                reply.report(hashes.visible(), hashes.matches, "Список уже получен. Сохраняю отчёт…");
                return 1;
            }
            return feedback(reply, existing.received()
                    ? "Отчёт уже получен; ожидаю сохранения в ClickHouse…"
                    : "Отчёт уже запрашивается. Ответ появится здесь.", false);
        }
        return feedback(reply, "Для " + name(target) + " уже ожидается отчёт.", true);
    }

    private static int requestOnline(ServerPlayer target, Reply reply) {
        if (!target.connection.hasChannel(ClientReportPayloads.Request.TYPE)
                || !target.connection.hasChannel(ClientReportPayloads.Report.TYPE)) {
            return feedback(reply, "Клиент " + name(target) + " не поддерживает отчёты.", true);
        }
        UUID uuid = target.getUUID();
        var existing = PENDING.get(uuid);
        if (existing != null) return joinPending(target, existing, reply);

        long nowNanos = System.nanoTime();
        if (reply != null) {
            Long last = LAST_MANUAL_REQUEST.get(uuid);
            if (last != null && nowNanos - last < MANUAL_COOLDOWN_NANOS) {
                return feedback(reply, "Повторите ручное обновление через несколько секунд.", true);
            }
        }
        var server = target.getServer();
        // Automatic joins retry in the bounded scanner after storage warmup, without rehashing clients while DB is down.
        // An explicit manual request still delivers inventory independently of persistence.
        if (reply == null && LoggerRuntime.clientReportStorage() == null) return 0;
        // Manual refresh is independent of the automatic 24h interval.
        // Never reserve a day BEFORE we have a confirmed report.
        if (reply == null) {
            long now = System.currentTimeMillis();
            if (LoggerServerData.get(server.overworld()).nextClientReportAt(uuid, now) != 0
                    || AUTO_RETRY_AT.getOrDefault(uuid, 0L) > now) return 0;
        }

        long id = ++lastId;
        HASH_COLLECTIONS.remove(uuid);
        PENDING.put(uuid, new Pending(id, target.connection, reply, false));
        if (reply != null) LAST_MANUAL_REQUEST.put(uuid, nowNanos);
        PacketDistributor.sendToPlayer(target, new ClientReportPayloads.Request(id));
        feedback(reply, "Запрос отправлен игроку " + name(target) + ".", false);
        String targetName = name(target);
        ServerTickScheduler.schedule(server, TIMEOUT_TICKS, () -> {
            Pending expired = PENDING.get(uuid);
            if (expired != null && expired.id() == id && !expired.received()) {
                PENDING.remove(uuid);
                deferAutomaticRetry(uuid);
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
            LoggerNetwork.executeClientReportRead(() -> {
                try {
                    var snapshot = storage.findClientReport(playerId, playerName);
                    Matches matches = snapshot == null || reply.requestId() == null
                            ? new Matches(List.of(), List.of()) : matchReport(snapshot.report(), TriggerRules.configured());
                    server.execute(() -> {
                        if (generation != reportGeneration || !enabled() || !LoggerConfig.isEnabled()
                                || !canDeliver(reply.source())) return;
                        if (snapshot == null || !snapshot.receivedAt().plus(Duration.ofHours(24)).isAfter(Instant.now())) {
                            feedback(reply, "Нет сохранённого отчёта игрока " + playerName + " за последние 24 часа.", true);
                        } else reply.report(snapshot, matches);
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

    static List<String> matchPacks(List<String> packs, String configured) {
        if (packs.isEmpty() || configured.isBlank()) return List.of();
        var names = new ArrayList<String>();
        for (String raw : configured.split(",")) {
            String watched = normalizePack(raw);
            if (!watched.isEmpty()) names.add(watched);
        }
        if (names.isEmpty()) return List.of();

        var matches = new ArrayList<String>();
        for (String pack : packs) {
            String name = normalizePack(pack);
            for (String watched : names) {
                if (matchesPack(name, watched)) {
                    matches.add(pack);
                    break;
                }
            }
        }
        return List.copyOf(matches);
    }

    private static String normalizePack(String raw) {
        String name = raw.strip().toLowerCase(Locale.ROOT);
        if (name.startsWith("file/")) name = name.substring(5);
        if (name.endsWith(".zip")) name = name.substring(0, name.length() - 4);
        return PACK_SEPARATORS.matcher(name).replaceAll(" ").strip();
    }

    private static boolean matchesPack(String name, String watched) {
        if (name.equals(watched)) return true;
        int version = watched.length() + 1;
        if (name.length() <= version || !name.startsWith(watched) || name.charAt(version - 1) != ' ') return false;
        if (version < name.length() && name.charAt(version) == 'v') version++;
        return version < name.length() && Character.isDigit(name.charAt(version));
    }

    private static List<String> splitRules(String value) {
        return java.util.Arrays.stream(value.split(",")).map(ClientReportNetwork::normalizePack)
                .filter(word -> !word.isEmpty()).distinct().limit(128).toList();
    }

    private static Matches matchReport(ClientReportPayloads.Report report, TriggerRules rules) {
        var packs = matchPacks(report.packs(), rules.packs());
        if (!rules.words().isEmpty()) {
            var combined = new java.util.LinkedHashSet<>(packs);
            for (String pack : report.packs()) {
                if (containsWord(normalizePack(pack), rules.words())) combined.add(pack);
            }
            packs = List.copyOf(combined);
        }
        if (rules.mods().isEmpty() && rules.words().isEmpty()) return new Matches(packs, List.of());
        var mods = new ArrayList<String>();
        for (var mod : report.mods()) {
            String id = normalizePack(mod.id());
            String name = normalizePack(mod.displayName());
            if (rules.mods().contains(id) || rules.mods().contains(name)
                    || containsWord(id, rules.words()) || containsWord(name, rules.words())) mods.add(mod.id());
        }
        return new Matches(packs, List.copyOf(mods));
    }

    private static boolean containsWord(String value, List<String> words) {
        for (String word : words) if (value.contains(word)) return true;
        return false;
    }

    private static boolean canReceiveAlerts(ServerPlayer player) {
        return PermissionUtil.hasOperatorNode(player, ALERT_PERMISSION);
    }

    static void handleAlerts(ClientReportAlertPayloads.Query query, IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer admin)
                    || !admin.connection.hasChannel(ClientReportAlertPayloads.View.TYPE)
                    || !canReceiveAlerts(admin)) return;
            var data = LoggerServerData.get(admin.getServer().overworld());
            switch (query.action()) {
                case ENABLE -> data.setClientReportAlerts(admin.getUUID(), true);
                case DISABLE -> data.setClientReportAlerts(admin.getUUID(), false);
                case READ -> {}
            }
            sendAlerts(admin, data.clientReportAlerts(admin.getUUID()));
        });
    }

    private static void sendAlerts(ServerPlayer admin, boolean subscribed) {
        if (!admin.connection.hasChannel(ClientReportAlertPayloads.View.TYPE)) return;
        pruneAlerts(Instant.now());
        PacketDistributor.sendToPlayer(admin, alertView(subscribed));
    }

    private static void pruneAlerts(Instant now) {
        var since = now.minus(Duration.ofHours(24));
        while (!ALERTS.isEmpty() && ALERTS.getFirst().time().isBefore(since)) ALERTS.removeFirst();
    }

    private static ClientReportAlertPayloads.View alertView(boolean subscribed) {
        var history = subscribed ? ALERTS.reversed().stream().map(AuditAlert::text).toList() : List.<String>of();
        return new ClientReportAlertPayloads.View(subscribed, history);
    }

    private static void notifyMatches(MinecraftServer server, String targetName, Matches matches) {
        if (matches.empty()) return;
        String summary = targetName + " · совпадений: моды " + matches.mods().size() + ", ресурс-паки " + matches.packs().size();
        String example = matches.mods().isEmpty() ? matches.packs().getFirst() : matches.mods().getFirst();
        String line = REPORT_TIME.format(Instant.now().atZone(ZoneId.systemDefault())) + " · " + summary + " · " + example;
        var now = Instant.now();
        pruneAlerts(now);
        ALERTS.addLast(new AuditAlert(now, line));
        while (ALERTS.size() > ClientReportAlertPayloads.MAX_ALERTS) ALERTS.removeFirst();
        var message = Component.literal("[AvilixLogger] " + summary + ". Подробнее в «Уведомлениях об отчётах».")
                .withStyle(ChatFormatting.GOLD);
        var data = LoggerServerData.get(server.overworld());
        ClientReportAlertPayloads.View view = null;
        for (var admin : server.getPlayerList().getPlayers()) {
            if (!data.clientReportAlerts(admin.getUUID()) || !canReceiveAlerts(admin)) continue;
            admin.sendSystemMessage(message);
            if (admin.connection.hasChannel(ClientReportAlertPayloads.View.TYPE)) {
                if (view == null) view = alertView(true);
                PacketDistributor.sendToPlayer(admin, view);
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
        showList(source, "Моды", report.mods().stream().map(mod -> mod.label() + " · " + mod.version()).toList(), gui);
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
