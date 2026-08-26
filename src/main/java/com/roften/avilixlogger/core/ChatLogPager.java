package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Cursor pagination whose database work never runs on the server tick. */
public final class ChatLogPager {
    private static final AtomicLong SEQUENCES = new AtomicLong();
    private static final Map<UUID, Long> LATEST = new ConcurrentHashMap<>();
    private static volatile ThreadPoolExecutor executor = createExecutor();

    private ChatLogPager() {}

    private static ThreadPoolExecutor createExecutor() {
        AtomicInteger number = new AtomicInteger();
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, "AvilixLogger-ChatQuery-" + number.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        };
        ThreadPoolExecutor result = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), factory, new ThreadPoolExecutor.AbortPolicy());
        result.prestartAllCoreThreads();
        return result;
    }

    private static synchronized ThreadPoolExecutor executor() {
        if (executor == null || executor.isShutdown()) executor = createExecutor();
        return executor;
    }

    public static int pageSize() {
        return Math.max(1, LoggerConfig.VALUES.chatPageSize.get());
    }

    public static void renderAndSend(ServerLevel level, ServerPlayer player, LastQueryManager.State state) {
        render(level, player, state);
    }

    public static void render(ServerLevel level, ServerPlayer player, LastQueryManager.State state) {
        if (level == null || player == null || state == null || player.server == null) return;

        UUID playerId = player.getUUID();
        long sequence = SEQUENCES.incrementAndGet();
        LATEST.put(playerId, sequence);
        QuerySnapshot snapshot = new QuerySnapshot(state.baseQuery.copy(), state.currentBeforeId(),
                state.pageIndex(), state.title);
        WeakReference<ServerPlayer> playerRef = new WeakReference<>(player);
        MinecraftServer server = player.server;
        player.sendSystemMessage(Component.literal("[Логгер] Загрузка страницы...").withStyle(ChatFormatting.AQUA));

        try {
            executor().execute(() -> {
                try {
                    PageResult result = query(level, snapshot, () -> Thread.currentThread().isInterrupted()
                            || !Long.valueOf(sequence).equals(LATEST.get(playerId)));
                    if (result == null || !Long.valueOf(sequence).equals(LATEST.get(playerId))) return;
                    server.execute(() -> deliver(level, playerRef, playerId, sequence, state, snapshot, result));
                } catch (Throwable error) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Chat log query failed", error);
                    server.execute(() -> {
                        if (!Long.valueOf(sequence).equals(LATEST.get(playerId))) return;
                        ServerPlayer live = playerRef.get();
                        if (live != null && live.isAlive()) {
                            live.sendSystemMessage(Component.literal("Ошибка полного запроса логов. Неполные данные не показаны; смотри server log.")
                                    .withStyle(ChatFormatting.RED));
                        }
                    });
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            player.sendSystemMessage(Component.literal("Очередь запросов логов занята. Повтори через несколько секунд.")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static PageResult query(ServerLevel level, QuerySnapshot snapshot,
                                    java.util.function.BooleanSupplier cancelled) {
        int size = pageSize();
        int desired = size + 1;
        LogQuery base = snapshot.query.copy();
        base.beforeId = snapshot.beforeId;
        boolean postFilters = hasPostFilters(base);
        int fetchLimit = postFilters ? Math.min(8000, Math.max(desired * 40, 500)) : desired;
        ArrayList<LogEntry> filtered = new ArrayList<>(desired);
        long scanBeforeId = base.beforeId;
        boolean exhausted = false;

        while (filtered.size() < desired && !cancelled.getAsBoolean()) {
            LogQuery pageQuery = base.copy();
            pageQuery.beforeId = scanBeforeId;
            pageQuery.limit = fetchLimit;
            List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(pageQuery);
            if (raw == null || raw.isEmpty()) {
                exhausted = true;
                break;
            }
            for (LogEntry entry : raw) {
                if (!postFilters || matchesPostFilters(entry, base)) {
                    filtered.add(entry);
                    if (filtered.size() >= desired) break;
                }
            }
            long nextScanBeforeId = raw.get(raw.size() - 1).id;
            if (nextScanBeforeId <= 0L || nextScanBeforeId == scanBeforeId) {
                exhausted = true;
                break;
            }
            scanBeforeId = nextScanBeforeId;
            if (raw.size() < pageQuery.limit) {
                exhausted = true;
                break;
            }
            if (!postFilters) break;
        }
        if (cancelled.getAsBoolean()) return null;

        boolean hasNext = filtered.size() > size || (postFilters && !exhausted);
        List<LogEntry> page = filtered.size() > size
                ? List.copyOf(filtered.subList(0, size)) : List.copyOf(filtered);
        long nextCursor = page.isEmpty() ? 0L : page.get(page.size() - 1).id;
        return new PageResult(page, hasNext && nextCursor > 0L, nextCursor);
    }

    private static void deliver(ServerLevel level, WeakReference<ServerPlayer> playerRef, UUID playerId,
                                long sequence, LastQueryManager.State requestedState,
                                QuerySnapshot snapshot, PageResult result) {
        if (!Long.valueOf(sequence).equals(LATEST.get(playerId))) return;
        ServerPlayer player = playerRef.get();
        if (player == null || !player.isAlive()) return;
        LastQueryManager.State liveState = LastQueryManager.get(player);
        if (liveState != requestedState || liveState.currentBeforeId() != snapshot.beforeId) return;

        liveState.nextCursorCandidate = result.nextCursor;
        liveState.hasNext = result.hasNext;
        MutableComponent header = Component.literal("[Логгер] ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(snapshot.title == null ? "Логи" : snapshot.title).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  (стр. " + snapshot.pageIndex + ")").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(header);
        if (result.entries.isEmpty()) {
            player.sendSystemMessage(Component.literal("Нет записей.").withStyle(ChatFormatting.GRAY));
        } else {
            for (LogEntry entry : result.entries) player.sendSystemMessage(LogText.toChatLine(level, entry));
        }
        renderNav(player, liveState);
    }

    private static boolean hasPostFilters(LogQuery q) {
        return q != null && ((q.owner != null && !q.owner.isBlank())
                || (q.blockIdFilter != null && !q.blockIdFilter.isBlank())
                || (q.planeNameFilter != null && !q.planeNameFilter.isBlank())
                || (q.extraTextFilter != null && !q.extraTextFilter.isBlank()));
    }

    private static boolean matchesPostFilters(LogEntry e, LogQuery q) {
        if (e == null || q == null) return false;
        if (q.owner != null && !q.owner.isBlank() && !PlaneLogFilters.matchesOwner(e, q.owner)) return false;
        String block = normalizeNeedle(q.blockIdFilter);
        if (!block.isBlank() && !containsAny(block, e.blockBefore, e.blockAfter, e.source, e.extra)) return false;
        String plane = normalizeNeedle(q.planeNameFilter);
        if (!plane.isBlank() && !containsAny(plane, e.entityType, e.entityNbt, e.source, e.extra)) return false;
        String text = normalizeNeedle(q.extraTextFilter);
        return text.isBlank() || containsAny(text, e.source, e.extra, e.entityType, e.itemStackNbt);
    }

    private static boolean containsAny(String needle, String... values) {
        if (needle == null || needle.isBlank()) return true;
        if (values == null) return false;
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static String normalizeNeedle(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static void renderNav(ServerPlayer player, LastQueryManager.State state) {
        boolean hasPrev = state.cursors.size() > 1;
        boolean hasNext = state.hasNext && state.nextCursorCandidate > 0;
        MutableComponent nav = Component.literal("[ ").withStyle(ChatFormatting.DARK_GRAY);
        nav.append(hasPrev
                ? Component.literal("« Назад").withStyle(ChatFormatting.AQUA)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page prev")))
                : Component.literal("« Назад").withStyle(ChatFormatting.DARK_GRAY));
        nav.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        nav.append(hasNext
                ? Component.literal("Вперёд »").withStyle(ChatFormatting.AQUA)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page next")))
                : Component.literal("Вперёд »").withStyle(ChatFormatting.DARK_GRAY));
        nav.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));
        nav.append(Component.literal("В начало").withStyle(ChatFormatting.YELLOW)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page first"))));
        nav.append(Component.literal(" ]").withStyle(ChatFormatting.DARK_GRAY));
        player.sendSystemMessage(nav);
    }

    public static void clear(ServerPlayer player) {
        if (player != null) LATEST.remove(player.getUUID());
    }

    public static synchronized void shutdown() {
        LATEST.clear();
        if (executor != null) executor.shutdownNow();
    }

    private record QuerySnapshot(LogQuery query, long beforeId, int pageIndex, String title) {}
    private record PageResult(List<LogEntry> entries, boolean hasNext, long nextCursor) {}
}
