package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Non-blocking placeholder storage used while the real DB-backed storage is
 * being initialized on a background thread. This prevents player login / tick
 * thread stalls when MySQL is slow or temporarily unavailable.
 */
public final class PendingLogStorage implements LogStorage {

    private final ArrayBlockingQueue<LogEntry> pending =
            new ArrayBlockingQueue<>(Math.max(10_000, LoggerConfig.VALUES.dbQueueCapacity.get()));

    private volatile LogStorage delegate;

    @Override
    public void append(LogEntry entry) {
        LogStorage d = delegate;
        if (d != null) {
            d.append(entry);
            return;
        }
        if (entry != null) {
            pending.offer(entry);
        }
    }

    @Override
    public List<LogEntry> query(LogQuery q) {
        LogStorage d = delegate;
        return d != null ? d.query(q) : List.of();
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery q) {
        LogStorage d = delegate;
        return d != null ? d.queryReverse(q) : List.of();
    }

    @Override
    public void shutdown() {
        LogStorage d = delegate;
        if (d != null) {
            d.shutdown();
        }
        pending.clear();
    }

    public void setDelegate(LogStorage delegate) {
        this.delegate = delegate;
        if (delegate == null) return;

        LogEntry entry;
        while ((entry = pending.poll()) != null) {
            delegate.append(entry);
        }
    }

    public void reset() {
        delegate = null;
        pending.clear();
    }
}
