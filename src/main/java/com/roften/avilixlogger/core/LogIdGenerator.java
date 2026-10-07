package com.roften.avilixlogger.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Monotonic ids used by ClickHouse cursor pagination.
 *
 * The high bits are the millisecond timestamp, the low 20 bits are a per-JVM
 * sequence. A backward clock adjustment cannot reverse the capture order.
 */
public final class LogIdGenerator {
    private static final AtomicLong LAST = new AtomicLong();

    private LogIdGenerator() {}

    public static long next(long tsMillis) {
        long base = tsMillis > 0 ? tsMillis : System.currentTimeMillis();
        long candidate = base << 20;
        return LAST.updateAndGet(previous -> Math.max(candidate, previous + 1));
    }

    public static long ensure(LogEntry entry) {
        if (entry == null) return 0L;
        if (entry.id <= 0L) {
            entry.id = next(entry.ts);
        }
        return entry.id;
    }
}
