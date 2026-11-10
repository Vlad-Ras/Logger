package com.roften.avilixlogger.net;

import com.mojang.authlib.GameProfile;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

final class ClientReportQueue {
    private static final int CAPACITY = 128;
    enum Result { QUEUED, ALREADY_QUEUED, FULL }

    record Ticket(GameProfile administrator) {}
    private record Waiting(Ticket ticket, long queuedAt, ScheduledFuture<?> expiry) {}

    private final ScheduledExecutorService executor;
    private final Map<String, Waiting> waiting = new HashMap<>();

    ClientReportQueue(ScheduledExecutorService executor) {
        this.executor = executor;
    }

    synchronized Result add(String name, GameProfile administrator) {
        var current = waiting.get(name);
        long now = System.nanoTime();
        if (current != null && now - current.queuedAt() < ClientReportCache.RETENTION_NANOS) return Result.ALREADY_QUEUED;
        if (current != null) remove(name);
        if (waiting.size() >= CAPACITY) return Result.FULL;
        var expiry = executor.schedule(() -> expire(name, now), ClientReportCache.RETENTION_NANOS, TimeUnit.NANOSECONDS);
        waiting.put(name, new Waiting(new Ticket(administrator), now, expiry));
        return Result.QUEUED;
    }

    synchronized Ticket find(String name) {
        var entry = waiting.get(name);
        if (entry == null) return null;
        if (System.nanoTime() - entry.queuedAt() < ClientReportCache.RETENTION_NANOS) return entry.ticket();
        remove(name);
        return null;
    }

    synchronized void cancel(String name) {
        remove(name);
    }

    synchronized void clear() {
        waiting.values().forEach(entry -> entry.expiry().cancel(false));
        waiting.clear();
    }

    private synchronized void expire(String name, long queuedAt) {
        var entry = waiting.get(name);
        if (entry != null && entry.queuedAt() == queuedAt) waiting.remove(name);
    }

    private Waiting remove(String name) {
        var entry = waiting.remove(name);
        if (entry != null) entry.expiry().cancel(false);
        return entry;
    }
}
