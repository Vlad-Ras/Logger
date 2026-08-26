package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.core.ChatLogPager;
import com.roften.avilixlogger.core.LastQueryManager;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LogQuery;
import com.roften.avilixlogger.core.LoggerRuntime;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * NeoForge (1.21+) payload-based networking for optional client GUI.
 *
 * Design goal:
 * - If the player has the client mod: /log gui opens an in-game screen and pulls log pages from server.
 * - If not: everything still works via chat commands. No disconnects.
 */
public final class LoggerNetwork {
    private LoggerNetwork() {}

    private static ThreadPoolExecutor createGuiExecutor(String threadNamePrefix, int threads, int queueSize) {
        ThreadFactory tf = new ThreadFactory() {
            private final AtomicInteger n = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, threadNamePrefix + n.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Thread.NORM_PRIORITY - 1);
                return t;
            }
        };
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads, threads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueSize),
                tf,
                new ThreadPoolExecutor.AbortPolicy()
        );
        executor.prestartAllCoreThreads();
        return executor;
    }

    private static final ThreadPoolExecutor PAGE_EXECUTOR = createGuiExecutor("AvilixLogger-GUI-PAGE-", 2, 256);
    private static final ThreadPoolExecutor DETAILS_EXECUTOR = createGuiExecutor("AvilixLogger-GUI-DETAILS-", 2, 128);
    private static final ScheduledExecutorService GUI_WATCHDOG_EXECUTOR = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AvilixLogger-GUI-WATCHDOG");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });

    private static final long GUI_PAGE_LONG_QUERY_WARNING_MS = 6_500L;
    private static final long GUI_DETAILS_LONG_QUERY_WARNING_MS = 4_500L;

    private static final java.util.Map<java.util.UUID, Long> LAST_GUI_PAGE_REQUEST_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> LAST_GUI_PAGE_REQUEST_SIG =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LAST_GUI_DETAILS_REQUEST_AT =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, String> LAST_GUI_DETAILS_REQUEST_SIG =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LATEST_GUI_PAGE_SEQ =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> LATEST_GUI_DETAILS_SEQ =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> PENDING_GUI_PAGE_SEQ =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<java.util.UUID, Long> PENDING_GUI_DETAILS_SEQ =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final AtomicLong GUI_PAGE_SEQ_GENERATOR = new AtomicLong();
    private static final AtomicLong GUI_DETAILS_SEQ_GENERATOR = new AtomicLong();

    /** Tracks which players actually have the client mod. */
    private static final Map<UUID, Boolean> CLIENT_PRESENT = new ConcurrentHashMap<>();

    /** Per-player GUI filter state (button-driven). */
    private static final Map<UUID, GuiFilters> GUI_FILTERS = new ConcurrentHashMap<>();

    public static boolean isClientPresent(ServerPlayer player) {
        return player != null && Boolean.TRUE.equals(CLIENT_PRESENT.get(player.getUUID()));
    }

    public static void clear(ServerPlayer player) {
        if (player != null) {
            UUID uuid = player.getUUID();
            CLIENT_PRESENT.remove(uuid);
            GUI_FILTERS.remove(uuid);
            LAST_GUI_PAGE_REQUEST_AT.remove(uuid);
            LAST_GUI_PAGE_REQUEST_SIG.remove(uuid);
            LAST_GUI_DETAILS_REQUEST_AT.remove(uuid);
            LAST_GUI_DETAILS_REQUEST_SIG.remove(uuid);
            LATEST_GUI_PAGE_SEQ.remove(uuid);
            LATEST_GUI_DETAILS_SEQ.remove(uuid);
            PENDING_GUI_PAGE_SEQ.remove(uuid);
            PENDING_GUI_DETAILS_SEQ.remove(uuid);
            LastQueryManager.clear(player);
            ChatLogPager.clear(player);
            pruneQueuedPageTasks(uuid);
            pruneQueuedDetailsTasks(uuid);
        }
    }

    public static void shutdown() {
        PAGE_EXECUTOR.shutdownNow();
        DETAILS_EXECUTOR.shutdownNow();
        GUI_WATCHDOG_EXECUTOR.shutdownNow();
        LAST_GUI_PAGE_REQUEST_AT.clear();
        LAST_GUI_PAGE_REQUEST_SIG.clear();
        LAST_GUI_DETAILS_REQUEST_AT.clear();
        LAST_GUI_DETAILS_REQUEST_SIG.clear();
        CLIENT_PRESENT.clear();
        GUI_FILTERS.clear();
        LATEST_GUI_PAGE_SEQ.clear();
        LATEST_GUI_DETAILS_SEQ.clear();
        PENDING_GUI_PAGE_SEQ.clear();
        PENDING_GUI_DETAILS_SEQ.clear();
    }

    private static long nextGuiPageSeq(UUID playerId) {
        long seq = GUI_PAGE_SEQ_GENERATOR.incrementAndGet();
        LATEST_GUI_PAGE_SEQ.put(playerId, seq);
        PENDING_GUI_PAGE_SEQ.put(playerId, seq);
        return seq;
    }

    private static long nextGuiDetailsSeq(UUID playerId) {
        long seq = GUI_DETAILS_SEQ_GENERATOR.incrementAndGet();
        LATEST_GUI_DETAILS_SEQ.put(playerId, seq);
        PENDING_GUI_DETAILS_SEQ.put(playerId, seq);
        return seq;
    }

    private static void completeGuiPageSeq(UUID playerId, long seq) {
        if (playerId != null) PENDING_GUI_PAGE_SEQ.remove(playerId, seq);
    }

    private static void completeGuiDetailsSeq(UUID playerId, long seq) {
        if (playerId != null) PENDING_GUI_DETAILS_SEQ.remove(playerId, seq);
    }

    private static void pruneQueuedPageTasks(UUID playerId) {
        if (playerId == null) return;
        PAGE_EXECUTOR.getQueue().removeIf(r -> r instanceof GuiPageTask task && playerId.equals(task.playerId));
    }

    private static void pruneQueuedDetailsTasks(UUID playerId) {
        if (playerId == null) return;
        DETAILS_EXECUTOR.getQueue().removeIf(r -> r instanceof GuiDetailsTask task && playerId.equals(task.playerId));
    }

    private static LogRow statusRow(ServerLevel level, Component text) {
        String dim = level == null ? "" : level.dimension().location().toString();
        return new LogRow(0L, dim, 0, 0, 0, text, false, new long[0]);
    }

    private static void sendGuiPageStatus(ServerPlayer sp, ServerLevel level, LastQueryManager.State state, Component text) {
        if (sp == null) return;
        String title = state == null || state.title == null ? "Логи" : state.title;
        int page = state == null ? 1 : state.pageIndex();
        boolean hasPrev = state != null && state.hasPrev();
        PacketDistributor.sendToPlayer(sp, new S2CLogPagePayload(title, page, hasPrev, false, List.of(statusRow(level, text))));
    }

    private static Component longPageQueryMessage() {
        return Component.literal("Запрос всё ещё обрабатывается. Он не будет обрезан; можно дождаться результата или изменить фильтры.")
                .withStyle(net.minecraft.ChatFormatting.YELLOW);
    }

    private static Component longDetailsQueryMessage() {
        return Component.literal("Детали всё ещё загружаются. Запрос продолжает выполняться в фоне.")
                .withStyle(net.minecraft.ChatFormatting.YELLOW);
    }

    private static void scheduleLongPageWarning(ServerPlayer sp, long seq, ServerLevel level, LastQueryManager.State state) {
        if (sp == null) return;
        final UUID playerId = sp.getUUID();
        final java.lang.ref.WeakReference<ServerPlayer> ref = new java.lang.ref.WeakReference<>(sp);
        GUI_WATCHDOG_EXECUTOR.schedule(() -> {
            Long latestSeq = LATEST_GUI_PAGE_SEQ.get(playerId);
            Long pendingSeq = PENDING_GUI_PAGE_SEQ.get(playerId);
            if (latestSeq == null || latestSeq.longValue() != seq || pendingSeq == null || pendingSeq.longValue() != seq) return;
            ServerPlayer p = ref.get();
            if (p == null || p.server == null || !p.isAlive()) return;
            p.server.execute(() -> {
                Long serverLatestSeq = LATEST_GUI_PAGE_SEQ.get(playerId);
                Long serverPendingSeq = PENDING_GUI_PAGE_SEQ.get(playerId);
                if (serverLatestSeq == null || serverLatestSeq.longValue() != seq || serverPendingSeq == null || serverPendingSeq.longValue() != seq) return;
                ServerPlayer live = ref.get();
                if (live == null || live.server == null || !live.isAlive()) return;
                sendGuiPageStatus(live, level, state, longPageQueryMessage());
            });
        }, GUI_PAGE_LONG_QUERY_WARNING_MS, TimeUnit.MILLISECONDS);
    }

    private static void scheduleLongDetailsWarning(ServerPlayer sp, long seq, C2SRequestDetailsPayload payload) {
        if (sp == null || payload == null) return;
        final UUID playerId = sp.getUUID();
        final java.lang.ref.WeakReference<ServerPlayer> ref = new java.lang.ref.WeakReference<>(sp);
        GUI_WATCHDOG_EXECUTOR.schedule(() -> {
            Long latestSeq = LATEST_GUI_DETAILS_SEQ.get(playerId);
            Long pendingSeq = PENDING_GUI_DETAILS_SEQ.get(playerId);
            if (latestSeq == null || latestSeq.longValue() != seq || pendingSeq == null || pendingSeq.longValue() != seq) return;
            ServerPlayer p = ref.get();
            if (p == null || p.server == null || !p.isAlive()) return;
            p.server.execute(() -> {
                Long serverLatestSeq = LATEST_GUI_DETAILS_SEQ.get(playerId);
                Long serverPendingSeq = PENDING_GUI_DETAILS_SEQ.get(playerId);
                if (serverLatestSeq == null || serverLatestSeq.longValue() != seq || serverPendingSeq == null || serverPendingSeq.longValue() != seq) return;
                ServerPlayer live = ref.get();
                if (live == null || live.server == null || !live.isAlive()) return;
                PacketDistributor.sendToPlayer(live, new S2CLogDetailsPayload(payload.entryId(), payload.mode(), List.of(longDetailsQueryMessage())));
            });
        }, GUI_DETAILS_LONG_QUERY_WARNING_MS, TimeUnit.MILLISECONDS);
    }

    private static Page errorPage(ServerLevel level, LastQueryManager.State state, String message) {
        String title = state == null || state.title == null ? "Логи" : state.title;
        int page = state == null ? 1 : state.pageIndex();
        boolean hasPrev = state != null && state.hasPrev();
        Component line = Component.literal(message == null || message.isBlank() ? "Ошибка запроса логов." : message)
                .withStyle(net.minecraft.ChatFormatting.RED);
        return new Page(title, page, hasPrev, false, List.of(statusRow(level, line)));
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        // IMPORTANT: client is optional. Channels must be OPTIONAL, иначе кикает клиентов без мода.
        PayloadRegistrar r = event.registrar(AvilixLoggerMod.MOD_ID);

        // Try to set network version if API supports it
        try {
            Object rr = r.getClass().getMethod("versioned", String.class).invoke(r, "1");
            if (rr instanceof PayloadRegistrar pr) r = pr;
        } catch (Throwable ignored) {}

        // Mark as optional if API supports it
        try {
            Object rr = r.getClass().getMethod("optional").invoke(r);
            if (rr instanceof PayloadRegistrar pr) r = pr;
        } catch (Throwable ignored) {}

        // Client -> Server
        r.playToServer(C2SHelloPayload.TYPE, C2SHelloPayload.STREAM_CODEC, LoggerNetwork::handleHello);
        r.playToServer(C2SOpenGuiPayload.TYPE, C2SOpenGuiPayload.STREAM_CODEC, LoggerNetwork::handleOpenGuiRequest);
        r.playToServer(C2SRequestPagePayload.TYPE, C2SRequestPagePayload.STREAM_CODEC, LoggerNetwork::handleRequestPage);
        r.playToServer(C2SRequestDetailsPayload.TYPE, C2SRequestDetailsPayload.STREAM_CODEC, LoggerNetwork::handleRequestDetails);

        // Server -> Client (отправляются только тем, у кого есть клиент-мод; запрос приходит с клиента)
        r.playToClient(S2COpenGuiPayload.TYPE, S2COpenGuiPayload.STREAM_CODEC, LoggerNetwork::handleOpenGuiClient);
        r.playToClient(S2CLogPagePayload.TYPE, S2CLogPagePayload.STREAM_CODEC, LoggerNetwork::handleLogPageClient);
        r.playToClient(S2CLogDetailsPayload.TYPE, S2CLogDetailsPayload.STREAM_CODEC, LoggerNetwork::handleDetailsClient);
    }

    // -------- client handlers (reflection-dispatched) --------

    private static void handleOpenGuiClient(S2COpenGuiPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("open", new Class<?>[0], new Object[0]));
    }

    private static void handleLogPageClient(S2CLogPagePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("acceptPage", new Class<?>[] { S2CLogPagePayload.class }, new Object[] { payload }));
    }

    private static void handleDetailsClient(S2CLogDetailsPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> invokeClientHook("acceptDetails", new Class<?>[] { S2CLogDetailsPayload.class }, new Object[] { payload }));
    }

    private static void invokeClientHook(String method, Class<?>[] sig, Object[] args) {
        try {
            // Avoid classloading on dedicated server.
            Class<?> c = Class.forName("com.roften.avilixlogger.client.gui.LogViewerClientHooks");
            c.getMethod(method, sig).invoke(null, args);
        } catch (Throwable ignored) {
            // No client mod / wrong side / or a mismatched version — GUI just won't be available.
        }
    }

    private static final class GuiPageTask implements Runnable {
        private final UUID playerId;
        private final java.lang.ref.WeakReference<ServerPlayer> playerRef;
        private final long seq;
        private final ServerLevel level;
        private final LastQueryManager.State snapshotState;
        private final boolean aggregated;
        private final GuiFilters filters;

        private GuiPageTask(ServerPlayer sp, long seq, ServerLevel level, LastQueryManager.State snapshotState, boolean aggregated, GuiFilters filters) {
            this.playerId = sp.getUUID();
            this.playerRef = new java.lang.ref.WeakReference<>(sp);
            this.seq = seq;
            this.level = level;
            this.snapshotState = snapshotState;
            this.aggregated = aggregated;
            this.filters = filters;
        }

        @Override
        public void run() {
            try {
                Long latestSeq = LATEST_GUI_PAGE_SEQ.get(playerId);
                if (latestSeq == null || latestSeq.longValue() != seq) return;

                Page page;
                try {
                    page = buildPage(level, snapshotState, aggregated, filters,
                            () -> Thread.currentThread().isInterrupted()
                                    || !Long.valueOf(seq).equals(LATEST_GUI_PAGE_SEQ.get(playerId)));
                } catch (Throwable t) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] GUI page query failed", t);
                    page = errorPage(level, snapshotState, "Ошибка запроса логов. Смотри server log.");
                }

                latestSeq = LATEST_GUI_PAGE_SEQ.get(playerId);
                if (latestSeq == null || latestSeq.longValue() != seq) return;

                ServerPlayer sp = playerRef.get();
                if (sp == null || sp.server == null || !sp.isAlive()) return;

                final Page pageToSend = page;
                sp.server.execute(() -> {
                    Long serverLatestSeq = LATEST_GUI_PAGE_SEQ.get(playerId);
                    if (serverLatestSeq == null || serverLatestSeq.longValue() != seq) return;

                    ServerPlayer livePlayer = playerRef.get();
                    if (livePlayer == null || livePlayer.server == null || !livePlayer.isAlive()) return;

                    LastQueryManager.State current = LastQueryManager.get(livePlayer);
                    if (current != null) {
                        current.nextCursorCandidate = snapshotState.nextCursorCandidate();
                        current.hasNext = snapshotState.hasNext();
                    }

                    PacketDistributor.sendToPlayer(livePlayer,
                            new S2CLogPagePayload(
                                    pageToSend.title,
                                    pageToSend.pageIndex,
                                    pageToSend.hasPrev,
                                    pageToSend.hasNext,
                                    pageToSend.rows
                            ));
                });
            } finally {
                completeGuiPageSeq(playerId, seq);
            }
        }
    }

    private static final class GuiDetailsTask implements Runnable {
        private final UUID playerId;
        private final java.lang.ref.WeakReference<ServerPlayer> playerRef;
        private final long seq;
        private final ServerLevel level;
        private final C2SRequestDetailsPayload payload;

        private GuiDetailsTask(ServerPlayer sp, long seq, ServerLevel level, C2SRequestDetailsPayload payload) {
            this.playerId = sp.getUUID();
            this.playerRef = new java.lang.ref.WeakReference<>(sp);
            this.seq = seq;
            this.level = level;
            this.payload = payload;
        }

        @Override
        public void run() {
            try {
                Long latestSeq = LATEST_GUI_DETAILS_SEQ.get(playerId);
                if (latestSeq == null || latestSeq.longValue() != seq) return;

                List<Component> lines;
                try {
                    lines = switch (payload.mode()) {
                        case RAW -> buildRawLines(level, payload.entryId(), payload.rawIds());
                        case DETAILS -> buildDetailsLines(level, payload.entryId(), payload.rawIds());
                        case JSON -> buildJsonLines(level, payload.entryId());
                    };
                } catch (Throwable t) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] GUI details query failed", t);
                    lines = List.of(Component.literal("Ошибка загрузки деталей. Смотри server log.").withStyle(net.minecraft.ChatFormatting.RED));
                }

                latestSeq = LATEST_GUI_DETAILS_SEQ.get(playerId);
                if (latestSeq == null || latestSeq.longValue() != seq) return;

                ServerPlayer sp = playerRef.get();
                if (sp == null || sp.server == null || !sp.isAlive()) return;

                final List<Component> linesToSend = lines;
                sp.server.execute(() -> {
                    Long serverLatestSeq = LATEST_GUI_DETAILS_SEQ.get(playerId);
                    if (serverLatestSeq == null || serverLatestSeq.longValue() != seq) return;

                    ServerPlayer livePlayer = playerRef.get();
                    if (livePlayer == null || livePlayer.server == null || !livePlayer.isAlive()) return;

                    PacketDistributor.sendToPlayer(livePlayer,
                            new S2CLogDetailsPayload(payload.entryId(), payload.mode(), linesToSend));
                });
            } finally {
                completeGuiDetailsSeq(playerId, seq);
            }
        }
    }

    // -------- handlers --------

    private static void handleHello(C2SHelloPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (ctx.player() instanceof ServerPlayer sp) {
                CLIENT_PRESENT.put(sp.getUUID(), true);
            }
        });
    }

    private static void handleOpenGuiRequest(C2SOpenGuiPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!Boolean.TRUE.equals(CLIENT_PRESENT.get(sp.getUUID()))) return; // client addon absent
            if (!hasGuiPermission(sp)) return;
            PacketDistributor.sendToPlayer(sp, new S2COpenGuiPayload());
        });
    }

    private static void handleRequestPage(C2SRequestPagePayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;

            long now = System.currentTimeMillis();
            String requestSig = String.valueOf(payload.nav()) + "|" + payload.aggregated() + "|" + String.valueOf(payload.filters());
            long last = LAST_GUI_PAGE_REQUEST_AT.getOrDefault(sp.getUUID(), 0L);
            String lastSig = LAST_GUI_PAGE_REQUEST_SIG.get(sp.getUUID());

            if (now - last < 300 && java.util.Objects.equals(lastSig, requestSig)) {
                return; // игнорируем только дубль того же самого GUI-запроса
            }
            LAST_GUI_PAGE_REQUEST_AT.put(sp.getUUID(), now);
            LAST_GUI_PAGE_REQUEST_SIG.put(sp.getUUID(), requestSig);


            if (!hasGuiPermission(sp)) {
                PacketDistributor.sendToPlayer(sp, new S2CLogPagePayload("Logger", 1, false, false,
                        List.of(new LogRow(0L, sp.serverLevel().dimension().location().toString(), 0, 0, 0,
                                Component.literal("Нет прав на GUI (avilixlogger.gui)").withStyle(net.minecraft.ChatFormatting.RED), false, new long[0]))));
                return;
            }

            GuiFilters gf = payload.filters() == null ? GuiFilters.DEFAULT : payload.filters();
            long seq = nextGuiPageSeq(sp.getUUID());
            GuiFilters prevGf = GUI_FILTERS.get(sp.getUUID());

            // Build (or reuse) a base query. If filters changed, restart pagination.
            LastQueryManager.State st = LastQueryManager.get(sp);
            if (st == null || prevGf == null || !prevGf.equals(gf) || payload.nav() == C2SRequestPagePayload.Nav.FIRST) {
                LogQuery q = buildBaseGuiQuery(sp, gf);
                st = LastQueryManager.set(sp, q, "Логи (GUI)");
                GUI_FILTERS.put(sp.getUUID(), gf);
            }

            // Apply navigation.
            switch (payload.nav()) {
                case NEXT -> {
                    if (st.hasNext() && st.nextCursorCandidate() > 0) {
                        LastQueryManager.next(sp, st.nextCursorCandidate());
                    }
                }
                case PREV -> {
                    if (st.hasPrev()) LastQueryManager.prev(sp);
                }
                case FIRST -> LastQueryManager.first(sp);
            }
            st = LastQueryManager.get(sp);
            if (st == null) return;

            ServerLevel lvl = safeLevel(sp, st.baseQuery.dim);
            if (lvl == null) lvl = sp.serverLevel();

            // Use latest filter state (may have been updated above).
            gf = GUI_FILTERS.getOrDefault(sp.getUUID(), gf);

            GuiFilters finalGf = gf;
            LastQueryManager.State liveState = st;
            LastQueryManager.State snapshotState = new LastQueryManager.State(
                    liveState.baseQuery.copy(),
                    new ArrayDeque<>(liveState.cursors),
                    liveState.title
            );
            snapshotState.nextCursorCandidate = liveState.nextCursorCandidate();
            snapshotState.hasNext = liveState.hasNext();
            ServerLevel finalLvl = lvl;

            pruneQueuedPageTasks(sp.getUUID());
            if (PAGE_EXECUTOR.getQueue().remainingCapacity() <= 0) {
                pruneQueuedPageTasks(sp.getUUID());
            }

            sendGuiPageStatus(sp, finalLvl, snapshotState, Component.literal("Загрузка логов...").withStyle(net.minecraft.ChatFormatting.AQUA));

            try {
                PAGE_EXECUTOR.execute(new GuiPageTask(sp, seq, finalLvl, snapshotState, payload.aggregated(), finalGf));
                scheduleLongPageWarning(sp, seq, finalLvl, snapshotState);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                completeGuiPageSeq(sp.getUUID(), seq);
                sendGuiPageStatus(sp, finalLvl, snapshotState, Component.literal("Очередь GUI-запросов перегружена. Повтори запрос через пару секунд.").withStyle(net.minecraft.ChatFormatting.RED));
            }
        });
    }

    private static void handleRequestDetails(C2SRequestDetailsPayload payload, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer sp)) return;
            if (!hasGuiPermission(sp)) return;

            long now = System.currentTimeMillis();
            String requestSig = payload.mode().name() + "|" + payload.entryId() + "|" + payload.dimHint() + "|" + (payload.rawIds() == null ? 0 : java.util.Arrays.hashCode(payload.rawIds()));
            long last = LAST_GUI_DETAILS_REQUEST_AT.getOrDefault(sp.getUUID(), 0L);
            String lastSig = LAST_GUI_DETAILS_REQUEST_SIG.get(sp.getUUID());
            if (now - last < 250 && java.util.Objects.equals(lastSig, requestSig)) {
                return;
            }
            LAST_GUI_DETAILS_REQUEST_AT.put(sp.getUUID(), now);
            LAST_GUI_DETAILS_REQUEST_SIG.put(sp.getUUID(), requestSig);

            // Resolve level
            ServerLevel lvl = safeLevel(sp, payload.dimHint());
            if (lvl == null) {
                LastQueryManager.State st = LastQueryManager.get(sp);
                lvl = st != null ? safeLevel(sp, st.baseQuery.dim) : null;
            }
            if (lvl == null) lvl = sp.serverLevel();

            long seq = nextGuiDetailsSeq(sp.getUUID());
            ServerLevel finalLvl = lvl;
            pruneQueuedDetailsTasks(sp.getUUID());
            if (DETAILS_EXECUTOR.getQueue().remainingCapacity() <= 0) {
                pruneQueuedDetailsTasks(sp.getUUID());
            }
            try {
                DETAILS_EXECUTOR.execute(new GuiDetailsTask(sp, seq, finalLvl, payload));
                scheduleLongDetailsWarning(sp, seq, payload);
            } catch (java.util.concurrent.RejectedExecutionException rejected) {
                completeGuiDetailsSeq(sp.getUUID(), seq);
                PacketDistributor.sendToPlayer(sp, new S2CLogDetailsPayload(payload.entryId(), payload.mode(),
                        List.of(Component.literal("Очередь деталей GUI перегружена. Повтори запрос.").withStyle(net.minecraft.ChatFormatting.RED))));
            }
        });
    }

    // -------- server-side helpers --------

    private static ServerLevel safeLevel(ServerPlayer sp, String dimId) {
        if (sp == null || sp.server == null || dimId == null || dimId.isBlank()) return null;
        try {
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, ResourceLocation.parse(dimId));
            return sp.server.getLevel(key);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static LogQuery buildBaseGuiQuery(ServerPlayer sp, GuiFilters gf) {
        LogQuery q = new LogQuery();
        ServerLevel level = sp.serverLevel();

        long now = System.currentTimeMillis();
        q.untilTs = now;

        // Time: custom minutes override presets.
        int customTime = gf != null ? gf.customTimeMinutes() : -1;
        if (customTime >= 0) {
            q.sinceTs = now - (customTime * 60_000L);
        } else {
            // Presets (minutes): 5m, 30m, 2h, 1d, 7d
            int[] mins = new int[] { 5, 30, 120, 1440, 10080 };
            int tidx = gf != null ? gf.timePresetIdx() : 1;
            if (tidx < 0 || tidx >= mins.length) tidx = 1;
            q.sinceTs = now - (mins[tidx] * 60_000L);
        }

        // Radius: custom blocks override presets. Radius=0 means WORLD.
        int r;
        int customRadius = gf != null ? gf.customRadiusBlocks() : -1;
        if (customRadius >= 0) {
            r = customRadius;
        } else {
            // Presets (blocks): 5, 20, 50, WORLD
            int[] radii = new int[] { 5, 20, 50, 0 };
            int ridx = gf != null ? gf.radiusPresetIdx() : 1;
            if (ridx < 0 || ridx >= radii.length) ridx = 1;
            r = radii[ridx];
        }
        if (r > 0) {
            q.dim = level.dimension().location().toString();
            q.minPos = sp.blockPosition().offset(-r, -r, -r);
            q.maxPos = sp.blockPosition().offset(r, r, r);
        } else {
            // GUI radius WORLD means global lookup across all logged dimensions, not just the player's current one.
            // ClickHouse treats dim="*" as a query across every logged dimension.
            q.dim = "*";
            q.minPos = null;
            q.maxPos = null;
        }

        // Actor
        if (gf != null && gf.actor() != null && !gf.actor().isBlank()) {
            q.actorName = gf.actor().trim();
        }

        // Planes: reuse gf.train() as an OWNER filter (like AirPlanesLogger lookup)
        if (gf != null && gf.typePresetIdx() == 8 && gf.train() != null && !gf.train().isBlank()) {
            q.owner = gf.train().trim();
        }

        if (gf != null) {
            String blockNeedle = normalizeFilterNeedle(gf.blockId());
            if (!blockNeedle.isBlank()) q.blockIdFilter = blockNeedle;

            String planeNeedle = normalizeFilterNeedle(gf.planeName());
            if (!planeNeedle.isBlank()) q.planeNameFilter = planeNeedle;

            if (gf.typePresetIdx() == 5) q.extraTextFilter = "train";
            else if (gf.typePresetIdx() == 6) q.extraTextFilter = "cannon";
        }

        // Types (preset index)
        q.types = mapTypePreset(gf != null ? gf.typePresetIdx() : 0);

        // GUI has its own page size; keep chat page size independent.
        q.limit = Math.max(1, com.roften.avilixlogger.LoggerConfig.VALUES.guiPageSize.get());
        q.debugSource = "gui";
        return q;
    }

    private static java.util.EnumSet<com.roften.avilixlogger.core.ActionType> mapTypePreset(int presetIdx) {
        // 0: ALL
        if (presetIdx <= 0) return null;
        return switch (presetIdx) {
            case 1 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_BREAK,
                    com.roften.avilixlogger.core.ActionType.BLOCK_PLACE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_USE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE
            );
            case 2 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.CONTAINER_OPEN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_CONTAINER_OPEN,
                    com.roften.avilixlogger.core.ActionType.CONTAINER_PUT,
                    com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE,
                    com.roften.avilixlogger.core.ActionType.GUI_OPEN
            );
            case 3 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH,
                    com.roften.avilixlogger.core.ActionType.ENTITY_MOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DISMOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_INTERACT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_ATTACK,
                    com.roften.avilixlogger.core.ActionType.PROJECTILE_SHOOT,
                    com.roften.avilixlogger.core.ActionType.PROJECTILE_HIT,
                    com.roften.avilixlogger.core.ActionType.PLAYER_DEATH,
                    com.roften.avilixlogger.core.ActionType.PLAYER_DIMENSION_CHANGE,
                    com.roften.avilixlogger.core.ActionType.PLAYER_RESPAWN,
                    com.roften.avilixlogger.core.ActionType.GUI_OPEN
            );
            case 4 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.ITEM_DROP,
                    com.roften.avilixlogger.core.ActionType.ITEM_PICKUP,
                    com.roften.avilixlogger.core.ActionType.ITEM_CRAFT,
                    com.roften.avilixlogger.core.ActionType.ITEM_SMELT,
                    com.roften.avilixlogger.core.ActionType.ITEM_USE,
                    com.roften.avilixlogger.core.ActionType.ITEM_USE_START,
                    com.roften.avilixlogger.core.ActionType.ITEM_USE_STOP,
                    com.roften.avilixlogger.core.ActionType.ITEM_CONSUME,
                    com.roften.avilixlogger.core.ActionType.PROJECTILE_SHOOT,
                    com.roften.avilixlogger.core.ActionType.PROJECTILE_HIT
            );
            // 5: TRAINS, 6: CANNON — we still keep a broad type set and then refine with extra filtering.
            case 5 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE,
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH,
                    com.roften.avilixlogger.core.ActionType.TRAIN_ASSEMBLE,
                    com.roften.avilixlogger.core.ActionType.TRAIN_DISASSEMBLE,
                    com.roften.avilixlogger.core.ActionType.TRAIN_SCHEDULE_TAKE,
                    com.roften.avilixlogger.core.ActionType.TRAIN_CONTROL_START,
                    com.roften.avilixlogger.core.ActionType.TRAIN_CONTROL_STOP,
                    com.roften.avilixlogger.core.ActionType.TRAIN_SCHEDULE_PUT
            );
            case 6 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.BLOCK_PLACE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_INTERACT,
                    com.roften.avilixlogger.core.ActionType.BLOCK_USE,
                    com.roften.avilixlogger.core.ActionType.BLOCK_ENTITY_NBT_CHANGE
            );
            case 7 -> java.util.EnumSet.of(
                    com.roften.avilixlogger.core.ActionType.CHAT_MESSAGE
            );
            case 8 -> java.util.EnumSet.of(
                    // Dedicated plane actions plus old/generic entity rows. The post-filter below
                    // keeps the GUI correct while ClickHouse/feed keeps the scan bounded.
                    com.roften.avilixlogger.core.ActionType.PLANE_PLACE,
                    com.roften.avilixlogger.core.ActionType.PLANE_REMOVE,
                    com.roften.avilixlogger.core.ActionType.PLANE_MOUNT,
                    com.roften.avilixlogger.core.ActionType.PLANE_PICKUP,
                    com.roften.avilixlogger.core.ActionType.ENTITY_OWNER_SET,
                    com.roften.avilixlogger.core.ActionType.ENTITY_SPAWN,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DEATH,
                    com.roften.avilixlogger.core.ActionType.ENTITY_MOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_DISMOUNT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_INTERACT,
                    com.roften.avilixlogger.core.ActionType.ENTITY_ATTACK
            );
            default -> null;
        };
    }

    /**
     * Produces a page similarly to {@link ChatLogPager} but returns components instead of sending to chat.
     */
    private static Page buildPage(ServerLevel level, LastQueryManager.State state, boolean aggregated, GuiFilters gf,
                                  java.util.function.BooleanSupplier cancelled) {
        int size = Math.max(1, com.roften.avilixlogger.LoggerConfig.VALUES.guiPageSize.get());

        LogQuery q = state.baseQuery.copy();
        q.beforeId = state.currentBeforeId();

        // GUI can aggregate spammy sequences (drops/places/container click-spam). To avoid returning
        // too few rows after aggregation, we overfetch.
        int desired = size + 1;
        int fetchLimit = Math.min(5000, desired * 12);
        if (gf != null && gf.typePresetIdx() == 8) {
            // Plane preset includes generic legacy entity rows too; keep the first DB slice bounded.
            fetchLimit = Math.min(2200, desired * 10);
            if (q.owner != null && !q.owner.isBlank()) fetchLimit = Math.min(2500, desired * 12);
            if (hasExtraGuiFilters(gf)) fetchLimit = Math.min(3500, Math.max(fetchLimit, desired * 16));
        } else {
            if (q.owner != null && !q.owner.isBlank()) fetchLimit = Math.min(8000, desired * 25);
            if (hasExtraGuiFilters(gf)) fetchLimit = Math.min(8000, Math.max(fetchLimit, desired * 40));
        }
        q.limit = fetchLimit;

        int neededFiltered = aggregated ? Math.max(desired * 6, size * 8) : desired;
        neededFiltered = Math.min(1200, Math.max(neededFiltered, desired));
        java.util.ArrayList<LogEntry> filtered = new java.util.ArrayList<>(Math.min(neededFiltered, fetchLimit));
        long scanBeforeId = q.beforeId;
        boolean exhausted = false;
        boolean brokeEarly = false;
        while (filtered.size() < neededFiltered && !cancelled.getAsBoolean()) {
            LogQuery pageQ = q.copy();
            pageQ.beforeId = scanBeforeId;
            List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(pageQ);
            if (raw == null || raw.isEmpty()) {
                exhausted = true;
                break;
            }

            List<LogEntry> pageFiltered = raw;

            // Optional owner post-filter (planes)
            if (q.owner != null && !q.owner.isBlank()) {
                ArrayList<LogEntry> tmp = new ArrayList<>(Math.min(raw.size(), desired * 4));
                for (LogEntry e : raw) {
                    if (com.roften.avilixlogger.core.PlaneLogFilters.matchesOwner(e, q.owner)) {
                        tmp.add(e);
                    }
                }
                pageFiltered = tmp;
            }

            // GUI-only extra filters that aren't part of LogQuery (train name, cannon, create-train events).
            pageFiltered = applyGuiExtraFilters(pageFiltered, gf, neededFiltered - filtered.size());
            if (!pageFiltered.isEmpty()) filtered.addAll(pageFiltered);

            long nextScanBeforeId = raw.get(raw.size() - 1).id;
            if (nextScanBeforeId <= 0L || nextScanBeforeId == scanBeforeId) {
                exhausted = true;
                break;
            }
            scanBeforeId = nextScanBeforeId;

            if (raw.size() < pageQ.limit) {
                exhausted = true;
                break;
            }
            if (filtered.size() >= neededFiltered) {
                brokeEarly = true;
                break;
            }
        }

        if (cancelled.getAsBoolean()) return errorPage(level, state, "Запрос заменён новым.");

        boolean hasNext;
        long nextCursorCandidate;
        List<LogRow> rows;
        if (aggregated) {
            // Aggregate ONLY for GUI output. Chat commands remain raw.
            AggregationResult agg = aggregateForGui(level, filtered, size);
            hasNext = agg.hasNext || !exhausted || brokeEarly;
            nextCursorCandidate = agg.nextCursorCandidate;
            rows = agg.rows;
        } else {
            // Raw mode (GUI only): just take first pageSize rows.
            List<LogRow> rr = new ArrayList<>(Math.min(size, filtered.size()));
            int n = 0;
            for (LogEntry e : filtered) {
                rr.add(LogRow.single(e.id, e.dim, e.x, e.y, e.z, com.roften.avilixlogger.core.LogText.toChatLine(level, e)));
                n++;
                if (n >= size) break;
            }
            hasNext = filtered.size() > size || !exhausted || brokeEarly;
            nextCursorCandidate = (rr.isEmpty() ? 0L : rr.get(rr.size() - 1).id());
            rows = List.copyOf(rr);
        }

        if (rows.isEmpty()) {
            // Cursor pagination is based on the last displayed row. Without a visible row there is
            // no safe cursor to continue from, so do not expose a broken "next" button.
            hasNext = false;
            nextCursorCandidate = 0L;
        }

        state.nextCursorCandidate = nextCursorCandidate;
        state.hasNext = hasNext;

        boolean hasPrev = state.cursors.size() > 1;

        List<com.roften.avilixlogger.net.LogRow> outRows = new ArrayList<>();
        if (rows.isEmpty()) {
            Component empty = Component.literal("Нет записей.");
            outRows.add(new com.roften.avilixlogger.net.LogRow(0L, level.dimension().location().toString(), 0, 0, 0, empty, false, new long[0]));
        } else {
            outRows.addAll(rows);
        }

        return new Page(state.title == null ? "Логи" : state.title, state.pageIndex(), hasPrev, hasNext, outRows);
    }

    private static boolean hasExtraGuiFilters(GuiFilters gf) {
        if (gf == null) return false;
        return (gf.train() != null && !gf.train().isBlank())
                || (gf.planeName() != null && !gf.planeName().isBlank())
                || (gf.blockId() != null && !gf.blockId().isBlank())
                || gf.typePresetIdx() == 5
                || gf.typePresetIdx() == 6
                || gf.typePresetIdx() == 8;
    }

    private static List<LogEntry> applyGuiExtraFilters(List<LogEntry> in, GuiFilters gf, int desired) {
        if (in == null || in.isEmpty()) return List.of();
        if (gf == null) return in;

        final String train = gf.train() == null ? "" : gf.train().trim();
        final boolean wantTrainName = !train.isBlank();
        final String planeNeedle = normalizeFilterNeedle(gf.planeName());
        final boolean wantPlaneName = !planeNeedle.isBlank();
        final String blockNeedle = normalizeFilterNeedle(gf.blockId());
        final boolean wantBlock = !blockNeedle.isBlank();
        final int typePreset = gf.typePresetIdx();
        final boolean wantCreateTrainsOnly = typePreset == 5;
        final boolean wantCannonOnly = typePreset == 6;

        // Planes: filter generic entity events down to plane-related ones.
        final boolean wantPlanesOnly = typePreset == 8;

        if (!wantTrainName && !wantPlaneName && !wantCreateTrainsOnly && !wantCannonOnly && !wantPlanesOnly && !wantBlock) return in;

        ArrayList<LogEntry> out = new ArrayList<>(Math.min(desired, in.size()));
        for (LogEntry e : in) {
            String extra = e.extra;
            if (extra == null) extra = "";

            if (wantBlock && !matchesBlockNeedle(e, blockNeedle)) continue;

            String extraLower = extra.toLowerCase(java.util.Locale.ROOT);
            if (wantCannonOnly) {
                // Heuristic: our cannon hooks write marker strings to extra.
                if (!extraLower.contains("schematic_cannon") && !extraLower.contains("schematicannon") && !extraLower.contains("create_cannon") && !extraLower.contains("cannon")) continue;
            }
            if (wantCreateTrainsOnly) {
                if (!extraLower.contains("create_train") && !extraLower.contains("carriage_contraption") && !extraLower.contains("train")) continue;
            }

            if (wantPlanesOnly) {
                // Always include dedicated plane action types (they're already plane-only).
                boolean dedicatedPlaneAction = e.type == com.roften.avilixlogger.core.ActionType.PLANE_PLACE
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_REMOVE
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_MOUNT
                        || e.type == com.roften.avilixlogger.core.ActionType.PLANE_PICKUP
                        || e.type == com.roften.avilixlogger.core.ActionType.ENTITY_OWNER_SET;

                if (!dedicatedPlaneAction) {
                    // For generic entity events, require that the entityType/extra looks like a plane/aircraft.
                    String et = e.entityType == null ? "" : e.entityType;
                    String etLower = et.toLowerCase(java.util.Locale.ROOT);
                    String exLower = extra.toLowerCase(java.util.Locale.ROOT);
                    boolean looksPlane = etLower.contains("aircraft") || etLower.contains("airplane") || etLower.contains("plane")
                            || exLower.contains("aircraft") || exLower.contains("airplane") || exLower.contains("plane")
                            || exLower.contains("immersive_aircraft") || etLower.contains("immersive_aircraft");
                    if (!looksPlane) continue;
                }

                // If user typed something into the "owner" box while in plane preset, interpret it as owner needle.
                if (wantTrainName) {
                    if (!com.roften.avilixlogger.core.PlaneLogFilters.matchesOwner(e, train)) continue;
                }
                // Optional plane name filter.
                if (wantPlaneName) {
                    if (!com.roften.avilixlogger.core.PlaneLogFilters.matchesPlaneName(e, planeNeedle)) continue;
                }
            } else {
                // Non-plane presets: keep original meaning of the "train" needle.
                if (wantTrainName) {
                    if (!extra.toLowerCase(java.util.Locale.ROOT).contains(train.toLowerCase(java.util.Locale.ROOT))) continue;
                }
            }
            out.add(e);
            if (out.size() >= desired) break;
        }
        return out;
    }


    private static boolean matchesBlockNeedle(LogEntry e, String blockNeedle) {
        if (blockNeedle == null || blockNeedle.isBlank() || e == null) return true;
        java.util.Locale L = java.util.Locale.ROOT;
        String bb = e.blockBefore == null ? "" : e.blockBefore.toLowerCase(L);
        String ba = e.blockAfter == null ? "" : e.blockAfter.toLowerCase(L);
        String src = e.source == null ? "" : e.source.toLowerCase(L);
        String extra = e.extra == null ? "" : e.extra.toLowerCase(L);

        if (bb.contains(blockNeedle) || ba.contains(blockNeedle) || src.contains(blockNeedle) || extra.contains(blockNeedle)) {
            return true;
        }

        return extractResourceLikeId(bb).equals(blockNeedle)
                || extractResourceLikeId(ba).equals(blockNeedle)
                || extractResourceLikeId(src).equals(blockNeedle)
                || extractResourceLikeId(extra).equals(blockNeedle);
    }

    private static String normalizeFilterNeedle(String s) {
        if (s == null) return "";
        String out = s.trim().toLowerCase(java.util.Locale.ROOT);
        while (!out.isEmpty()) {
            char c0 = out.charAt(0);
            if (c0 == '\'' || c0 == '"' || c0 == '`' || Character.isWhitespace(c0)) out = out.substring(1).trim();
            else break;
        }
        while (!out.isEmpty()) {
            char c1 = out.charAt(out.length() - 1);
            if (c1 == '\'' || c1 == '"' || c1 == '`' || Character.isWhitespace(c1)) out = out.substring(0, out.length() - 1).trim();
            else break;
        }
        String extracted = extractResourceLikeId(out);
        return extracted.isBlank() ? out : extracted;
    }

    private static String extractResourceLikeId(String s) {
        if (s == null || s.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([a-z0-9_.-]+:[a-z0-9_./-]+)")
                .matcher(s.toLowerCase(java.util.Locale.ROOT));
        return m.find() ? m.group(1) : "";
    }

    private static boolean hasGuiPermission(ServerPlayer sp) {
        try {
            return com.roften.avilixlogger.core.PermissionUtil.has(sp.createCommandSourceStack(), "avilixlogger.gui", 2);
        } catch (Throwable t) {
            return sp.hasPermissions(2);
        }
    }

    private static List<LogEntry> fetchByIdsBestEffort(ServerLevel level, long anchorId, long[] ids) {
        if (level == null) return List.of();
        if (ids == null || ids.length == 0) {
            LogQuery q = new LogQuery();
            q.dim = level.dimension().location().toString();
            q.beforeId = anchorId + 1;
            q.limit = 64;
            q.requireDetails = true;
            List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
            if (got == null || got.isEmpty()) {
                q.requireDetails = false;
                got = LoggerRuntime.storage(level).queryReverse(q);
            }
            if (got == null) return List.of();
            for (LogEntry e : got) if (e.id == anchorId) return List.of(e);
            return got.isEmpty() ? List.of() : List.of(got.get(0));
        }

        long max = anchorId;
        for (long id : ids) if (id > max) max = id;
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.beforeId = max + 1;
        q.limit = Math.min(2000, Math.max(64, ids.length * 16));
        q.requireDetails = true;
        List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
        if (got == null || got.isEmpty()) {
            q.requireDetails = false;
            got = LoggerRuntime.storage(level).queryReverse(q);
        }
        if (got == null || got.isEmpty()) return List.of();
        java.util.Set<Long> want = new java.util.HashSet<>();
        for (long id : ids) want.add(id);
        java.util.ArrayList<LogEntry> out = new java.util.ArrayList<>(ids.length);
        for (LogEntry e : got) if (want.contains(e.id)) out.add(e);
        return out;
    }

    private static List<Component> buildRawLines(ServerLevel level, long entryId, long[] rawIds) {
        List<LogEntry> es = fetchByIdsBestEffort(level, entryId, rawIds);
        if (es.isEmpty()) return List.of(Component.literal("(нет данных)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
        List<Component> out = new ArrayList<>(es.size());
        for (LogEntry e : es) {
            out.add(com.roften.avilixlogger.core.LogText.toChatLine(level, e));
        }
        return List.copyOf(out);
    }

    private static List<Component> buildDetailsLines(ServerLevel level, long entryId, long[] rawIds) {
        List<LogEntry> es = fetchByIdsBestEffort(level, entryId, rawIds);
        if (es.isEmpty()) return List.of(Component.literal("(нет данных)").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));

        // If this is a burst (multiple events), include a small timeline section for clarity.
        List<LogEntry> chronological = null;
        if (es.size() > 1) {
            chronological = new ArrayList<>(es);
            chronological.sort(java.util.Comparator.comparingLong(a -> a.ts));
        }

        // If it's a container burst: summarize put/take.
        boolean hasContainer = false;
        java.util.LinkedHashMap<String, ItemAgg> put = new java.util.LinkedHashMap<>();
        java.util.LinkedHashMap<String, ItemAgg> take = new java.util.LinkedHashMap<>();
        for (LogEntry e : es) {
            if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT || e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                hasContainer = true;
                int c = Math.max(1, e.count);
                String key = safeItemKey(e.itemStackNbt);
                Component name = safeItemComponent(level, e.itemStackNbt);
                if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT) addAgg(put, key, name, c);
                else addAgg(take, key, name, c);
            }
        }

        // If a single entry with BE snapshots, compute NBT diff.
        LogEntry first = es.get(0);
        List<Component> out = new ArrayList<>();
        out.add(Component.literal("Details").withStyle(net.minecraft.ChatFormatting.GOLD));

        if (chronological != null) {
            out.add(Component.literal("Timeline:").withStyle(net.minecraft.ChatFormatting.YELLOW));
            long t0 = chronological.get(0).ts;
            int limit = Math.min(120, chronological.size());
            for (int i = 0; i < limit; i++) {
                LogEntry e = chronological.get(i);
                double dt = (e.ts - t0) / 1000.0;
                String stamp = String.format(java.util.Locale.ROOT, "t+%.1fs ", dt);
                Component shortLine = com.roften.avilixlogger.core.LogText.toChatLine(level, e).copy().withStyle(net.minecraft.ChatFormatting.GRAY);
                out.add(Component.literal(stamp).withStyle(net.minecraft.ChatFormatting.DARK_GRAY).append(shortLine));
            }
            if (chronological.size() > limit) {
                out.add(Component.literal("… +" + (chronological.size() - limit) + " events").withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
            }
            out.add(Component.literal(" "));
        }

        if (hasContainer) {
            out.add(Component.literal("Изменения контейнера:").withStyle(net.minecraft.ChatFormatting.YELLOW));
            appendSignedList(out, "+ ", put, net.minecraft.ChatFormatting.GREEN);
            appendSignedList(out, "- ", take, net.minecraft.ChatFormatting.RED);
            return List.copyOf(out);
        }

        // If strict slot snapshots exist on the primary entry, build diff from them.
        if (first.containerSlotsBefore != null && first.containerSlotsAfter != null
                && !first.containerSlotsBefore.isBlank() && !first.containerSlotsAfter.isBlank()) {
            var agg = com.roften.avilixlogger.core.ContainerSlotDiffUtil.diffAggregated(first.containerSlotsBefore, first.containerSlotsAfter, level.registryAccess());
            if (agg != null && !agg.isEmpty()) {
                out.add(Component.literal("Изменения контейнера:").withStyle(net.minecraft.ChatFormatting.YELLOW));
                java.util.LinkedHashMap<String, ItemAgg> put2 = new java.util.LinkedHashMap<>();
                java.util.LinkedHashMap<String, ItemAgg> take2 = new java.util.LinkedHashMap<>();
                for (var ent : agg.entrySet()) {
                    int delta = ent.getValue();
                    if (delta == 0) continue;
                    String key = safeItemKey(ent.getKey());
                    Component nm = safeItemComponent(level, ent.getKey());
                    if (delta > 0) addAgg(put2, key, nm, delta);
                    else addAgg(take2, key, nm, Math.abs(delta));
                }
                appendSignedList(out, "+ ", put2, net.minecraft.ChatFormatting.GREEN);
                appendSignedList(out, "- ", take2, net.minecraft.ChatFormatting.RED);
                return List.copyOf(out);
            }
        }

        if (first.beBefore != null && first.beAfter != null && !first.beBefore.isBlank() && !first.beAfter.isBlank()) {
            var deltas = com.roften.avilixlogger.core.InventoryDiffUtil.diff(first.beBefore, first.beAfter, level.registryAccess());
            if (!deltas.isEmpty()) {
                out.add(Component.literal("Diff контейнера (NBT):").withStyle(net.minecraft.ChatFormatting.YELLOW));
                int shown = 0;
                for (var d : deltas) {
                    int dc = d.deltaCount();
                    String name = (d.representative() == null || d.representative().isEmpty()) ? "?" : d.representative().getHoverName().getString();
                    Component ln = Component.literal((dc > 0 ? "+" : "") + dc + " " + name)
                            .withStyle(dc > 0 ? net.minecraft.ChatFormatting.GREEN : net.minecraft.ChatFormatting.RED);
                    out.add(ln);
                    if (++shown >= 200) break;
                }
                return List.copyOf(out);
            }
        }

        // Fallback: show the raw single line as details.
        out.add(com.roften.avilixlogger.core.LogText.toChatLine(level, first).copy().withStyle(net.minecraft.ChatFormatting.GRAY));
        return List.copyOf(out);
    }

    private static List<Component> buildJsonLines(ServerLevel level, long entryId) {
        if (level == null) return List.of(Component.literal("{}").withStyle(net.minecraft.ChatFormatting.GRAY));
        LogQuery q = new LogQuery();
        q.dim = level.dimension().location().toString();
        q.beforeId = entryId + 1;
        q.limit = 64;
        q.requireDetails = true;
        List<LogEntry> got = LoggerRuntime.storage(level).queryReverse(q);
        if (got == null || got.isEmpty()) {
            q.requireDetails = false;
            got = LoggerRuntime.storage(level).queryReverse(q);
        }
        if (got == null) got = List.of();
        LogEntry target = null;
        for (LogEntry e : got) if (e.id == entryId) { target = e; break; }
        if (target == null) target = got.isEmpty() ? null : got.get(0);
        if (target == null) return List.of(Component.literal("{}").withStyle(net.minecraft.ChatFormatting.GRAY));

        java.util.LinkedHashMap<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", target.id);
        m.put("ts", target.ts);
        m.put("dim", target.dim);
        m.put("x", target.x);
        m.put("y", target.y);
        m.put("z", target.z);
        m.put("type", String.valueOf(target.type));
        m.put("actorUuid", target.actorUuid == null ? null : target.actorUuid.toString());
        m.put("actorName", target.actorName);
        // Target fields are not part of LogEntry in this project; keep entity info instead.
        m.put("entityType", target.entityType);
        m.put("entityUuid", target.entityUuid == null ? null : target.entityUuid.toString());
        m.put("count", target.count);
        m.put("blockBefore", target.blockBefore);
        m.put("blockAfter", target.blockAfter);
        m.put("itemStackNbt", target.itemStackNbt);
        m.put("extra", target.extra);
        m.put("beBefore", target.beBefore);
        m.put("beAfter", target.beAfter);

        String json = com.roften.avilixlogger.core.GzipJson.GSON.toJson(m);
        // Return as a single line component (clipboard-friendly). GUI will copy it.
        return List.of(Component.literal(json).withStyle(net.minecraft.ChatFormatting.GRAY));
    }

    private static void appendSignedList(List<Component> out, String prefix, java.util.LinkedHashMap<String, ItemAgg> map, net.minecraft.ChatFormatting color) {
        if (map == null || map.isEmpty()) return;
        // sort by count desc
        java.util.List<ItemAgg> list = new java.util.ArrayList<>(map.values());
        list.sort((a, b) -> Integer.compare(b.count, a.count));
        for (var it : list) {
            out.add(Component.literal(prefix).withStyle(color)
                    .append(it.name.copy().withStyle(color))
                    .append(Component.literal(" x" + it.count).withStyle(color)));
        }
    }

    private static final class ItemAgg {
        final Component name;
        int count;
        ItemAgg(Component name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private static void addAgg(java.util.LinkedHashMap<String, ItemAgg> map, String key, Component name, int count) {
        if (map == null) return;
        if (key == null || key.isBlank()) key = "?";
        ItemAgg agg = map.get(key);
        if (agg == null) map.put(key, new ItemAgg(name == null ? Component.literal("?") : name, count));
        else agg.count += count;
    }

    /** Stable aggregation key for itemStackNbt. */
    private static String safeItemKey(String itemSnbtOrId) {
        if (itemSnbtOrId == null) return "?";
        String s = itemSnbtOrId.trim();
        if (s.isEmpty()) return "?";

        // Plain registry id
        if (!s.startsWith("{") && s.contains(":")) return s;

        // Extract id from SNBT (supports both " and ' quoting, and even unquoted forms).
        try {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\bid\\s*:\\s*(?:\\\"|')?([a-z0-9_.-]+:[a-z0-9_./-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(s);
            if (m.find()) {
                String id = m.group(1);
                // keep NBT variants separate (avoid collapsing differently-tagged items)
                if (s.contains("tag:") || s.contains("components:")) id += "#" + Integer.toHexString(s.hashCode());
                return id;
            }
        } catch (Throwable ignored) {
        }

        return s;
    }

    /**
     * Returns a translatable component when possible (so client language applies).
     * Accepts either full ItemStack SNBT or a plain item id (minecraft:stone).
     */
    private static Component safeItemComponent(ServerLevel level, String itemSnbtOrId) {
        try {
            if (itemSnbtOrId == null || itemSnbtOrId.isBlank()) return Component.literal("?");
            String s = itemSnbtOrId.trim();

            // Plain id
            if (!s.startsWith("{") && s.contains(":")) {
                var rl = net.minecraft.resources.ResourceLocation.tryParse(s);
                if (rl != null) {
                    var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
                    if (it != null) return Component.translatable(it.getDescriptionId());
                }
                return Component.literal(s);
            }

            // Full SNBT
            net.minecraft.world.item.ItemStack st = com.roften.avilixlogger.core.NbtSerde.readItemStack(s, level.registryAccess());
            if (st != null && !st.isEmpty()) return st.getHoverName().copy();

            // Last resort: try to extract id from SNBT (supports both " and ' quoting, and unquoted forms).
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\\bid\\s*:\\s*(?:\\\"|')?([a-z0-9_.-]+:[a-z0-9_./-]+)", java.util.regex.Pattern.CASE_INSENSITIVE)
                    .matcher(s);
            if (m.find()) {
                String id = m.group(1);
                var rl = net.minecraft.resources.ResourceLocation.tryParse(id);
                if (rl != null) {
                    var it = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(rl).orElse(null);
                    if (it != null) return Component.translatable(it.getDescriptionId());
                }
                return Component.literal(id);
            }
            return Component.literal("?");
        } catch (Throwable t) {
            return Component.literal("?");
        }
    }

    // -------- GUI-only aggregation --------

    private static final long GUI_BURST_WINDOW_MS = 1200L;
    private static final long GUI_BURST_MAX_MS = 5000L;
    private static final int GUI_INLINE_TOP_N = 5;

    private static AggregationResult aggregateForGui(ServerLevel level, List<LogEntry> in, int pageSize) {
        if (in == null || in.isEmpty()) {
            return new AggregationResult(List.of(), false, 0L);
        }

        List<com.roften.avilixlogger.net.LogRow> out = new ArrayList<>(pageSize);

        // input is newest -> older
        Burst burst = null;
        long nextCursorCandidate = 0L;

        for (int i = 0; i < in.size(); i++) {
            LogEntry e = in.get(i);

            BurstKey key = BurstKey.of(e);
            if (key == null) {
                // flush pending burst
                if (burst != null) {
                    out.add(burst.toRow(level));
                    nextCursorCandidate = burst.oldestId();
                    burst = null;
                    if (out.size() >= pageSize) break;
                }
                out.add(com.roften.avilixlogger.net.LogRow.single(
                        e.id,
                        e.dim,
                        e.x,
                        e.y,
                        e.z,
                        com.roften.avilixlogger.core.LogText.toChatLine(level, e)
                ));
                nextCursorCandidate = e.id;
                if (out.size() >= pageSize) break;
                continue;
            }

            if (burst == null || !burst.canAccept(e, key)) {
                if (burst != null) {
                    out.add(burst.toRow(level));
                    nextCursorCandidate = burst.oldestId();
                    if (out.size() >= pageSize) break;
                }
                burst = new Burst(key, e);
            } else {
                burst.add(e);
            }
        }

        if (out.size() < pageSize && burst != null) {
            out.add(burst.toRow(level));
            nextCursorCandidate = burst.oldestId();
        }

        // if we didn't exhaust the input, there is more to show
        boolean hasNext = false;
        if (out.size() >= pageSize) {
            // We may have cut mid-input; consider that as next page exists if there are remaining rows.
            // Also keep "nextCursorCandidate" as the oldest id we included.
            // If we included exactly pageSize but still have input remaining -> next.
            hasNext = true;
        } else {
            // We used all available input; no next.
            hasNext = false;
        }

        return new AggregationResult(List.copyOf(out), hasNext, nextCursorCandidate);
    }

    private record AggregationResult(List<com.roften.avilixlogger.net.LogRow> rows, boolean hasNext, long nextCursorCandidate) {}

    private enum BurstKind {
        CONTAINER,
        ITEM,
        BLOCK,
    }

    private record BurstKey(BurstKind kind, String dim, java.util.UUID actor, int kx, int kz, com.roften.avilixlogger.core.ActionType type, String itemOrBlockKey) {
        static BurstKey of(LogEntry e) {
            if (e == null || e.type == null) return null;
            if (e.actorUuid == null) return null; // don't aggregate "system"; keep raw
            String dim = e.dim == null ? "" : e.dim;

            // Container: merge PUT+TAKE into one burst for the same container.
            if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT || e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                return new BurstKey(BurstKind.CONTAINER, dim, e.actorUuid, e.x, e.z, e.type, null);
            }

            // Items
            if (e.type == com.roften.avilixlogger.core.ActionType.ITEM_DROP || e.type == com.roften.avilixlogger.core.ActionType.ITEM_PICKUP) {
                String key = e.itemStackNbt == null ? "" : e.itemStackNbt;
                int cx = e.x >> 4;
                int cz = e.z >> 4;
                return new BurstKey(BurstKind.ITEM, dim, e.actorUuid, cx, cz, e.type, key);
            }

            // Blocks
            if (e.type == com.roften.avilixlogger.core.ActionType.BLOCK_BREAK || e.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) {
                String key = (e.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) ? (e.blockAfter == null ? "" : e.blockAfter)
                        : (e.blockBefore == null ? "" : e.blockBefore);
                int cx = e.x >> 4;
                int cz = e.z >> 4;
                return new BurstKey(BurstKind.BLOCK, dim, e.actorUuid, cx, cz, e.type, key);
            }

            return null;
        }
    }

    private static final class Burst {
        private final BurstKey key;
        private final List<LogEntry> events = new ArrayList<>();
        private long newestTs;
        private long oldestTs;
        private long newestId;
        private long oldestId;

        Burst(BurstKey key, LogEntry first) {
            this.key = key;
            add(first);
        }

        boolean canAccept(LogEntry e, BurstKey k) {
            if (e == null || k == null) return false;
            // Must match aggregation key.
            if (!k.equals(this.key)) return false;
            // Window based on newest event time.
            long dt = this.newestTs - e.ts;
            if (dt < 0) dt = 0;
            return dt <= GUI_BURST_WINDOW_MS && (this.newestTs - this.oldestTs) <= GUI_BURST_MAX_MS;
        }

        void add(LogEntry e) {
            if (e == null) return;
            events.add(e);
            if (events.size() == 1) {
                newestTs = oldestTs = e.ts;
                newestId = oldestId = e.id;
            } else {
                // list is newest->older; we append in that order
                oldestTs = e.ts;
                oldestId = e.id;
            }
        }

        long oldestId() { return oldestId; }

        com.roften.avilixlogger.net.LogRow toRow(ServerLevel level) {
            if (events.size() == 1) {
                LogEntry e = events.get(0);
                return com.roften.avilixlogger.net.LogRow.single(e.id, e.dim, e.x, e.y, e.z, com.roften.avilixlogger.core.LogText.toChatLine(level, e));
            }

            LogEntry first = events.get(0);
            String actor = (first.actorName != null && !first.actorName.isBlank()) ? first.actorName : "?";
            long durMs = Math.max(0L, newestTs - oldestTs);
            String dur = String.format(java.util.Locale.ROOT, "%.1fs", durMs / 1000.0);

            net.minecraft.network.chat.MutableComponent line;

            switch (key.kind) {
                case ITEM -> {
                    int total = 0;
                    java.util.LinkedHashMap<String, ItemAgg> top = new java.util.LinkedHashMap<>();
                    for (LogEntry e : events) {
                        total += Math.max(1, e.count);
                        int c = Math.max(1, e.count);
                        addAgg(top, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                    }
                    String verb = (key.type == com.roften.avilixlogger.core.ActionType.ITEM_DROP) ? "выбросил" : "подобрал";
                    line = Component.literal(actor + " " + verb + " x" + total + " (" + dur + ") ");
                    line = line.append(Component.literal("[")
                            .append(Component.empty().append(summarizeTop(top, GUI_INLINE_TOP_N))
                                    .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)))
                            .append(Component.literal("]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY))));
                }
                case BLOCK -> {
                    int total = events.size();
                    String verb = (key.type == com.roften.avilixlogger.core.ActionType.BLOCK_PLACE) ? "поставил" : "сломал";
                    String block = safeBlockName(level, key.itemOrBlockKey);
                    line = Component.literal(actor + " " + verb + " x" + total + " " + block + " (" + dur + ")");
                }
                case CONTAINER -> {
                    java.util.LinkedHashMap<String, ItemAgg> put = new java.util.LinkedHashMap<>();
                    java.util.LinkedHashMap<String, ItemAgg> take = new java.util.LinkedHashMap<>();
                    for (LogEntry e : events) {
                        int c = Math.max(1, e.count);
                        if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_PUT) {
                            addAgg(put, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                        } else if (e.type == com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE) {
                            addAgg(take, safeItemKey(e.itemStackNbt), safeItemComponent(level, e.itemStackNbt), c);
                        }
                    }
                    net.minecraft.network.chat.MutableComponent both = Component.empty();
                    if (!put.isEmpty()) {
                        both.append(Component.literal("+").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GREEN)));
                        both.append(Component.empty().append(summarizeTop(put, GUI_INLINE_TOP_N))
                                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    }
                    if (!put.isEmpty() && !take.isEmpty()) both.append(Component.literal(", ").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    if (!take.isEmpty()) {
                        both.append(Component.literal("-").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.RED)));
                        both.append(Component.empty().append(summarizeTop(take, GUI_INLINE_TOP_N))
                                .withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                    }

                    line = Component.literal(actor + " изменил контейнер (" + dur + ") ")
                            .append(Component.literal("[").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)))
                            .append(both)
                            .append(Component.literal("]").withStyle(style -> style.withColor(net.minecraft.ChatFormatting.GRAY)));
                }
                default -> {
                    line = Component.literal(actor + " сделал x" + events.size() + " (" + dur + ")");
                }
            }

            long[] rawIds = new long[events.size()];
            for (int i = 0; i < events.size(); i++) rawIds[i] = events.get(i).id;
            return new com.roften.avilixlogger.net.LogRow(first.id, first.dim, first.x, first.y, first.z, line, true, rawIds);
        }

        private static String safeBlockName(ServerLevel level, String blockStateSnbtOrId) {
            try {
                if (blockStateSnbtOrId == null || blockStateSnbtOrId.isBlank()) return "?";
                // Try to extract Name:"minecraft:stone" from BlockState SNBT.
                String s = blockStateSnbtOrId;
                int idx = s.indexOf("Name:\\\"");
                if (idx >= 0) {
                    int q1 = s.indexOf('"', idx + 5);
                    if (q1 >= 0) {
                        int q2 = s.indexOf('"', q1 + 1);
                        if (q2 > q1) {
                            return s.substring(q1 + 1, q2);
                        }
                    }
                }
                // Fallback: already an id.
                return s.length() > 64 ? s.substring(0, 64) : s;
            } catch (Throwable t) {
                return "?";
            }
        }

        private static Component summarizeTop(java.util.LinkedHashMap<String, ItemAgg> map, int topN) {
            if (map.isEmpty()) return Component.empty();
            java.util.List<ItemAgg> list = new java.util.ArrayList<>(map.values());
            list.sort((a, b) -> Integer.compare(b.count, a.count));
            net.minecraft.network.chat.MutableComponent out = Component.empty();
            int shown = 0;
            int rest = 0;
            for (int i = 0; i < list.size(); i++) {
                ItemAgg it = list.get(i);
                if (shown < topN) {
                    if (shown > 0) out.append(Component.literal(", "));
                    out.append(it.name.copy());
                    out.append(Component.literal(" x" + it.count));
                    shown++;
                } else {
                    rest++;
                }
            }
            if (rest > 0) {
                out.append(Component.literal(" +" + rest + " видов"));
            }
            return out;
        }
    }

    private record Page(String title, int pageIndex, boolean hasPrev, boolean hasNext, List<com.roften.avilixlogger.net.LogRow> rows) {}
}
