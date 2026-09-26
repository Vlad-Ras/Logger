package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders log query results into chat, with lightweight cursor-based pagination.
 */
public final class ChatLogPager {

    private ChatLogPager() {}

    public static int pageSize() {
        return Math.max(1, LoggerConfig.VALUES.chatPageSize.get());
    }

    public static void renderAndSend(ServerLevel level, ServerPlayer player, LastQueryManager.State state) {
        render(level, player, state);
    }

    public static void render(ServerLevel level, ServerPlayer player, LastQueryManager.State state) {
        if (level == null || player == null || state == null) return;

        int size = pageSize();

        LogQuery q = state.baseQuery.copy();
        q.beforeId = state.currentBeforeId();

        // Probe one extra record to know if there is a next page.
        // Sparse post-filters (owner/block/name) must scan several bounded chunks; otherwise
        // commands like /log --t 2h --r 0 --m planes --owner X can show false empty pages.
        int desired = size + 1;
        boolean hasPostFilters = hasPostFilters(q);
        int fetchLimit = hasPostFilters ? Math.min(5000, Math.max(desired * 25, 500)) : desired;
        q.limit = fetchLimit;

        List<LogEntry> filtered;
        boolean exhausted = false;
        boolean brokeEarly = false;
        boolean timedOut = false;
        long budgetMs = q.softBudgetMs > 0 ? Math.max(1500L, q.softBudgetMs) : 6000L;
        if (hasPostFilters) {
            filtered = new ArrayList<>(desired);
            long scanBeforeId = q.beforeId;
            int passes = 0;
            long deadlineNs = System.nanoTime() + budgetMs * 1_000_000L;
            while (passes < 10 && filtered.size() < desired) {
                if (System.nanoTime() >= deadlineNs) {
                    timedOut = true;
                    brokeEarly = true;
                    break;
                }
                LogQuery pageQ = q.copy();
                pageQ.beforeId = scanBeforeId;
                long queryStartedNs = System.nanoTime();
                List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(pageQ);
                long queryElapsedMs = (System.nanoTime() - queryStartedNs) / 1_000_000L;
                if (queryElapsedMs >= budgetMs) {
                    timedOut = true;
                    brokeEarly = true;
                }
                if (raw == null || raw.isEmpty()) {
                    exhausted = !timedOut;
                    break;
                }
                for (LogEntry e : raw) {
                    if (matchesPostFilters(e, q)) {
                        filtered.add(e);
                        if (filtered.size() >= desired) break;
                    }
                }
                scanBeforeId = raw.get(raw.size() - 1).id;
                passes++;
                if (raw.size() < pageQ.limit) {
                    exhausted = true;
                    break;
                }
            }
        } else {
            long queryStartedNs = System.nanoTime();
            filtered = LoggerRuntime.storage(level).queryReverse(q);
            long queryElapsedMs = (System.nanoTime() - queryStartedNs) / 1_000_000L;
            if (queryElapsedMs >= budgetMs) {
                timedOut = true;
                brokeEarly = true;
            }
            if (filtered == null) filtered = List.of();
            exhausted = timedOut ? false : filtered.size() < fetchLimit;
        }

        boolean hasNext = filtered.size() > size || (hasPostFilters && !exhausted) || brokeEarly;
        List<LogEntry> page = filtered;
        if (filtered.size() > size) page = new ArrayList<>(filtered.subList(0, size));

        long nextCursorCandidate = 0L;
        if (!page.isEmpty()) {
            nextCursorCandidate = page.get(page.size() - 1).id;
        }
        state.nextCursorCandidate = nextCursorCandidate;
        state.hasNext = hasNext;

        // Header
        MutableComponent header = Component.literal("[Логгер] ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal(state.title == null ? "Логи" : state.title).withStyle(ChatFormatting.GOLD))
                .append(Component.literal("  (стр. " + state.pageIndex() + ")").withStyle(ChatFormatting.GRAY));
        player.sendSystemMessage(header);

        if (page.isEmpty()) {
            if (timedOut) {
                player.sendSystemMessage(Component.literal("Не удалось получить информацию: запрос обрабатывался слишком долго и не вернул данные. Сузь время, радиус, тип, игрока или повтори запрос.").withStyle(ChatFormatting.YELLOW));
            } else {
                player.sendSystemMessage(Component.literal("Нет записей.").withStyle(ChatFormatting.GRAY));
            }
            renderNav(player, state);
            return;
        }

        if (timedOut) {
            player.sendSystemMessage(Component.literal("Часть информации могла не загрузиться: запрос обрабатывался слишком долго. Сузь фильтры для полного результата.").withStyle(ChatFormatting.YELLOW));
        }

        for (LogEntry e : page) {
            player.sendSystemMessage(LogText.toChatLine(level, e));
        }

        renderNav(player, state);
    }

    private static boolean hasPostFilters(LogQuery q) {
        if (q == null) return false;
        return (q.owner != null && !q.owner.isBlank())
                || (q.blockIdFilter != null && !q.blockIdFilter.isBlank())
                || (q.planeNameFilter != null && !q.planeNameFilter.isBlank())
                || (q.extraTextFilter != null && !q.extraTextFilter.isBlank());
    }

    private static boolean matchesPostFilters(LogEntry e, LogQuery q) {
        if (e == null || q == null) return false;
        if (q.owner != null && !q.owner.isBlank() && !PlaneLogFilters.matchesOwner(e, q.owner)) return false;
        String block = normalizeNeedle(q.blockIdFilter);
        if (!block.isBlank() && !containsAny(e, block, e.blockBefore, e.blockAfter, e.source, e.extra)) return false;
        String plane = normalizeNeedle(q.planeNameFilter);
        if (!plane.isBlank() && !containsAny(e, plane, e.entityType, e.entityNbt, e.source, e.extra)) return false;
        String text = normalizeNeedle(q.extraTextFilter);
        if (!text.isBlank() && !containsAny(e, text, e.source, e.extra, e.entityType, e.itemStackNbt)) return false;
        return true;
    }

    private static boolean containsAny(LogEntry e, String needle, String... values) {
        if (needle == null || needle.isBlank()) return true;
        if (values == null) return false;
        for (String value : values) {
            if (value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle)) return true;
        }
        return false;
    }

    private static String normalizeNeedle(String s) {
        return s == null ? "" : s.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private static void renderNav(ServerPlayer player, LastQueryManager.State state) {
        boolean hasPrev = state.cursors.size() > 1;
        boolean hasNext = state.hasNext && state.nextCursorCandidate > 0;

        MutableComponent nav = Component.empty();
        nav.append(Component.literal("[ ").withStyle(ChatFormatting.DARK_GRAY));

        // Prev
        if (hasPrev) {
            nav.append(Component.literal("« Назад").withStyle(ChatFormatting.AQUA)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page prev"))));
        } else {
            nav.append(Component.literal("« Назад").withStyle(ChatFormatting.DARK_GRAY));
        }

        nav.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        // Next
        if (hasNext) {
            nav.append(Component.literal("Вперёд »").withStyle(ChatFormatting.AQUA)
                    .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page next"))));
        } else {
            nav.append(Component.literal("Вперёд »").withStyle(ChatFormatting.DARK_GRAY));
        }

        nav.append(Component.literal(" | ").withStyle(ChatFormatting.DARK_GRAY));

        // First
        nav.append(Component.literal("В начало").withStyle(ChatFormatting.YELLOW)
                .withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/log page first"))));

        nav.append(Component.literal(" ]").withStyle(ChatFormatting.DARK_GRAY));
        player.sendSystemMessage(nav);
    }
}
