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
        // If owner filtering is enabled, we need to overfetch because filtering happens after DB read.
        int desired = size + 1;
        int fetchLimit = desired;
        if (q.owner != null && !q.owner.isBlank()) {
            fetchLimit = Math.min(2000, desired * 20);
        }
        q.limit = fetchLimit;

        List<LogEntry> raw = LoggerRuntime.storage(level).queryReverse(q);
        if (raw == null) raw = List.of();

        // Post-filter (planes owner filter): keep stable ordering.
        List<LogEntry> filtered;
        if (q.owner != null && !q.owner.isBlank()) {
            filtered = new ArrayList<>(Math.min(desired, raw.size()));
            for (LogEntry e : raw) {
                if (PlaneLogFilters.matchesOwner(e, q.owner)) {
                    filtered.add(e);
                    if (filtered.size() >= desired) break;
                }
            }
        } else {
            filtered = raw;
        }

        boolean hasNext = filtered.size() > size;
        List<LogEntry> page = filtered;
        if (hasNext) page = new ArrayList<>(filtered.subList(0, size));

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
            player.sendSystemMessage(Component.literal("Нет записей.").withStyle(ChatFormatting.GRAY));
            renderNav(player, state);
            return;
        }

        for (LogEntry e : page) {
            player.sendSystemMessage(LogText.toChatLine(level, e));
        }

        renderNav(player, state);
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
