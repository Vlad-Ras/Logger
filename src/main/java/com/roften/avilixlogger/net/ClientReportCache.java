package com.roften.avilixlogger.net;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Server-only, bounded cache. Expiry tasks share the network watchdog and do no server-thread work. */
final class ClientReportCache {
    static final int CAPACITY = 128;
    static final long RETENTION_NANOS = Duration.ofHours(24).toNanos();

    record Snapshot(String playerName, Instant receivedAt, ClientReportPayloads.Report report) {}

    private record Stored(Snapshot snapshot, long storedAtNanos, ScheduledFuture<?> expiry) {}

    private final ScheduledExecutorService executor;
    private final LongSupplier nanoTime;
    private final LinkedHashMap<UUID, Stored> reports = new LinkedHashMap<>();

    ClientReportCache(ScheduledExecutorService executor, LongSupplier nanoTime) {
        this.executor = executor;
        this.nanoTime = nanoTime;
    }

    synchronized Snapshot store(UUID player, String name, ClientReportPayloads.Report report) {
        long now = nanoTime.getAsLong();
        Snapshot snapshot = new Snapshot(name, Instant.now(), report);
        long requestId = report.id();
        var expiry = executor.schedule(() -> expire(player, requestId), RETENTION_NANOS, TimeUnit.NANOSECONDS);
        remove(player);
        reports.put(player, new Stored(snapshot, now, expiry));
        if (reports.size() > CAPACITY) remove(reports.firstEntry().getKey());
        return snapshot;
    }

    synchronized Snapshot find(UUID player) {
        Stored stored = reports.get(player);
        if (stored == null) return null;
        if (!expired(stored, nanoTime.getAsLong())) return stored.snapshot();
        remove(player);
        return null;
    }

    synchronized Snapshot find(String name) {
        long now = nanoTime.getAsLong();
        for (var stored : reports.sequencedValues().reversed()) {
            if (stored.snapshot().playerName().equalsIgnoreCase(name) && !expired(stored, now)) {
                return stored.snapshot();
            }
        }
        return null;
    }

    synchronized void clear() {
        for (var stored : reports.values()) stored.expiry().cancel(false);
        reports.clear();
    }

    private static boolean expired(Stored stored, long now) {
        return now - stored.storedAtNanos() >= RETENTION_NANOS;
    }

    private synchronized void expire(UUID player, long requestId) {
        Stored current = reports.get(player);
        if (current != null && current.snapshot().report().id() == requestId) reports.remove(player);
    }

    private void remove(UUID player) {
        Stored removed = reports.remove(player);
        if (removed != null) removed.expiry().cancel(false);
    }
}
