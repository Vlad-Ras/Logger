package com.roften.avilixlogger.core;

import java.util.List;

/**
 * Fallback storage used when the configured DB is unavailable.
 * Prevents server crashes while making it obvious that logging is disabled.
 */
public final class NoopLogStorage implements LogStorage {
    @Override
    public void append(LogEntry entry) {
        // no-op
    }

    @Override
    public List<LogEntry> query(LogQuery q) {
        throw new IllegalStateException("Logger storage is unavailable; check the ClickHouse connection error in server log");
    }

    @Override
    public List<LogEntry> queryReverse(LogQuery q) {
        throw new IllegalStateException("Logger storage is unavailable; check the ClickHouse connection error in server log");
    }

    @Override
    public void shutdown() {
        // no-op
    }
}
