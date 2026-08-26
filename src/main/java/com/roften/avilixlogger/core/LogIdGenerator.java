package com.roften.avilixlogger.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic ids used by ClickHouse cursor pagination.
 *
 * The high bits are the millisecond timestamp, the low 20 bits are a per-JVM
 * sequence. This keeps ids ordered by event time without relying on a database-side sequence.
 */
public final class LogIdGenerator {
    private static final AtomicLong SEQ = new AtomicLong();

    private LogIdGenerator() {}

    public static long next(long tsMillis) {
        long base = tsMillis > 0 ? tsMillis : System.currentTimeMillis();
        long seq = SEQ.getAndIncrement() & 0xFFFFFL;
        return (base << 20) | seq;
    }

    public static long ensure(LogEntry entry) {
        if (entry == null) return 0L;
        if (entry.id <= 0L) {
            entry.id = next(entry.ts);
        }
        return entry.id;
    }
}
