package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Non-blocking placeholder storage used while the real DB-backed storage is
 * being initialized on a background thread. This prevents player login / tick
 * thread stalls while ClickHouse is starting or temporarily unavailable.
 */
public final class PendingLogStorage implements LogStorage {

    private final ArrayBlockingQueue<LogEntry> pending =
            new ArrayBlockingQueue<>(Math.max(10_000, LoggerConfig.VALUES.clickHouseQueueCapacity.get()));
    private final long maxPayloadBytes = Math.max(16L, LoggerConfig.VALUES.clickHouseMaxQueuedPayloadMiB.get()) * 1024L * 1024L;
    private final java.util.concurrent.atomic.AtomicLong payloadBytes = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong dropped = new java.util.concurrent.atomic.AtomicLong();

    private volatile LogStorage delegate;

    @Override
    public void append(LogEntry entry) {
        LogStorage d = delegate;
        if (d != null) {
            d.append(entry);
            return;
        }
        if (entry != null) {
            long weight = PayloadSizeEstimator.estimate(entry);
            if (!reserve(weight)) {
                warnDropped();
                return;
            }
            if (!pending.offer(entry)) {
                payloadBytes.addAndGet(-weight);
                warnDropped();
            }
        }
    }

    @Override
    public List<LogEntry> query(LogQuery q) {
        LogStorage d = delegate;
        if (d == null) throw new IllegalStateException("ClickHouse storage is still initializing");
        return d.query(q);
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery q) {
        LogStorage d = delegate;
        if (d == null) throw new IllegalStateException("ClickHouse storage is still initializing");
        return d.queryReverse(q);
    }

    @Override
    public void shutdown() {
        LogStorage d = delegate;
        if (d != null) {
            d.shutdown();
        }
        pending.clear();
        payloadBytes.set(0L);
    }

    public void setDelegate(LogStorage delegate) {
        this.delegate = delegate;
        if (delegate == null) return;

        LogEntry entry;
        while ((entry = pending.poll()) != null) {
            payloadBytes.addAndGet(-PayloadSizeEstimator.estimate(entry));
            delegate.append(entry);
        }
    }

    public void reset() {
        delegate = null;
        pending.clear();
        payloadBytes.set(0L);
    }

    private boolean reserve(long bytes) {
        while (true) {
            long current = payloadBytes.get();
            if (bytes > maxPayloadBytes || current > maxPayloadBytes - bytes) return false;
            if (payloadBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private void warnDropped() {
        long count = dropped.incrementAndGet();
        if (count == 1L || count % 1_000L == 0L) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Startup queue rejected {} rows while ClickHouse initializes; queuedRows={}, estimatedPayloadMiB={}",
                    count, pending.size(), payloadBytes.get() / (1024L * 1024L));
        }
    }
}
