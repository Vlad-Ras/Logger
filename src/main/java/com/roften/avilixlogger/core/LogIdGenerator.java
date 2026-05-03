package com.roften.avilixlogger.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Storage-independent monotonic ids used by MySQL and ClickHouse.
 *
 * The high bits are the millisecond timestamp, the low 20 bits are a per-JVM
 * sequence. This keeps cursor pagination comparable across backends in dual
 * read/merge mode and avoids MySQL AUTO_INCREMENT vs ClickHouse generated id
 * divergence for new logs.
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
