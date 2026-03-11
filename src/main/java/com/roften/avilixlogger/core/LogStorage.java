package com.roften.avilixlogger.core;

import java.util.List;

/**
 * Storage abstraction.
 *
 * IMPORTANT: this mod is designed for high write throughput.
 * Implementations must not perform blocking I/O on the server tick thread.
 */
public interface LogStorage {

    /** Enqueue a log entry for persistence (non-blocking). */
    void append(LogEntry entry);

    /**
     * Query entries in chronological order.
     *
     * @param q query
     * @return at most q.limit entries
     */
    List<LogEntry> query(LogQuery q);

    /**
     * Query entries for rollback in reverse chronological order.
     * Implementations should push down the filters to the storage engine.
     */
    List<LogEntry> queryReverse(LogQuery q);

    /** Approximate queue fill percent, if supported by implementation. */
    default int queueFillPercent() { return 0; }

    /** Whether storage is currently in overload-protection mode. */
    default boolean isUnderPressure() { return false; }

    /** Flush and shutdown background writers. */
    void shutdown();
}
