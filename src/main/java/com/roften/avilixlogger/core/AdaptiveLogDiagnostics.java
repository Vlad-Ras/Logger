package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Low-cost counters for verifying adaptive capture on real modpacks without per-event log spam. */
public final class AdaptiveLogDiagnostics {
    private static final LongAdder ACCEPTED = new LongAdder();
    private static final LongAdder SUPPRESSED = new LongAdder();
    private static final ConcurrentHashMap<String, LongAdder> BY_SOURCE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, LongAdder> ADAPTER_FAILURES = new ConcurrentHashMap<>();

    private AdaptiveLogDiagnostics() {}

    public static void accepted(LogEntry entry) {
        ACCEPTED.increment();
        if (entry == null || entry.source == null || entry.source.isBlank()) return;
        BY_SOURCE.computeIfAbsent(entry.source, ignored -> new LongAdder()).increment();
    }

    public static void suppressedDuplicate() {
        SUPPRESSED.increment();
    }

    public static void adapterFailure(String adapterId) {
        String key = adapterId == null || adapterId.isBlank() ? "unknown" : adapterId;
        ADAPTER_FAILURES.computeIfAbsent(key, ignored -> new LongAdder()).increment();
    }

    public static void logSummary() {
        String sources = top(BY_SOURCE, 12);
        String failures = top(ADAPTER_FAILURES, 12);
        AvilixLoggerMod.LOGGER.info(
                "[AvilixLogger] Adaptive capture summary: accepted={}, duplicatesSuppressed={}, sources=[{}], adapterFailures=[{}]",
                ACCEPTED.sum(), SUPPRESSED.sum(), sources, failures);
    }

    private static String top(Map<String, LongAdder> counters, int limit) {
        return counters.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
                .limit(Math.max(1, limit))
                .map(entry -> entry.getKey() + "=" + entry.getValue().sum())
                .collect(Collectors.joining(", "));
    }
}
