package com.roften.avilixlogger.core;

import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player pagination state for chat log output.
 *
 * We intentionally implement cursor-based pagination (using {@link LogQuery#beforeId})
 * rather than OFFSET, to avoid heavy SQL scans on large tables.
 */
public final class LastQueryManager {

    public static final class State {
        public final LogQuery baseQuery;
        public final Deque<Long> cursors;
        public final String title;

        /**
         * Calculated after rendering a page: the cursor value to use for the next page
         * (i.e., the smallest id shown on the current page).
         */
        public volatile long nextCursorCandidate;

        /** Whether the rendered page indicates that there is at least one more page. */
        public volatile boolean hasNext;

        public State(LogQuery baseQuery, Deque<Long> cursors, String title) {
            this.baseQuery = baseQuery;
            this.cursors = cursors;
            this.title = title;
        }

        public long currentBeforeId() {
            Long v = cursors.peekLast();
            return v == null ? 0L : v;
        }

        public int pageIndex() {
            return Math.max(1, cursors.size());
        }

        public boolean hasPrev() {
            return cursors.size() > 1;
        }

        public boolean hasNext() {
            return hasNext;
        }

        public long nextCursorCandidate() {
            return nextCursorCandidate;
        }

    }

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();

    private LastQueryManager() {}

    public static State set(Player player, LogQuery baseQuery, String title) {
        if (player == null || baseQuery == null) return null;
        Deque<Long> cursors = new ArrayDeque<>();
        cursors.addLast(0L); // first page
        STATES.put(player.getUUID(), new State(baseQuery, cursors, title));
            return STATES.get(player.getUUID());
}

    public static State get(Player player) {
        if (player == null) return null;
        return STATES.get(player.getUUID());
    }

    public static void clear(Player player) {
        if (player == null) return;
        STATES.remove(player.getUUID());
    }

    public static void next(Player player, long nextCursorBeforeId) {
        if (player == null) return;
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        if (nextCursorBeforeId <= 0) return;
        s.cursors.addLast(nextCursorBeforeId);
    }

    public static boolean prev(Player player) {
        if (player == null) return false;
        State s = STATES.get(player.getUUID());
        if (s == null) return false;
        if (s.cursors.size() <= 1) return false;
        s.cursors.removeLast();
        return true;
    }

    public static void first(Player player) {
        if (player == null) return;
        State s = STATES.get(player.getUUID());
        if (s == null) return;
        s.cursors.clear();
        s.cursors.addLast(0L);
    }
}
