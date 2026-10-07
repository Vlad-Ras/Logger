package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** TickTask is an executor-age marker, not a delay timer. This queue uses actual tick deadlines. */
public final class ServerTickScheduler {
    private static final Map<MinecraftServer, Tasks> SERVERS = new IdentityHashMap<>();
    private ServerTickScheduler() {}

    public static void schedule(MinecraftServer server, int delay, Runnable action) {
        if (server == null || action == null) return;
        if (!server.isSameThread()) { server.execute(() -> schedule(server, delay, action)); return; }
        SERVERS.computeIfAbsent(server, ignored -> new Tasks()).add((long) server.getTickCount() + Math.max(1, delay), action);
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        Tasks tasks = SERVERS.get(event.getServer());
        if (tasks != null) tasks.runDue(event.getServer().getTickCount());
    }

    public static void finish(MinecraftServer server) {
        Tasks tasks = SERVERS.remove(server);
        if (tasks != null) tasks.runDue(Long.MAX_VALUE);
    }

    static final class Tasks {
        private record Task(long tick, long order, Runnable action) {}
        private final PriorityQueue<Task> queue = new PriorityQueue<>(Comparator.comparingLong(Task::tick).thenComparingLong(Task::order));
        private long order;
        void add(long tick, Runnable action) { queue.add(new Task(tick, order++, action)); }
        void runDue(long tick) {
            while (!queue.isEmpty() && queue.peek().tick <= tick) {
                Task next = queue.poll();
                try { next.action.run(); }
                catch (Throwable error) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Delayed capture failed", error); }
            }
        }
    }
}
