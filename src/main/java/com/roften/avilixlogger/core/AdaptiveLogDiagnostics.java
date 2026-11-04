package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;

import java.util.concurrent.atomic.LongAdder;

/** Low-cost counters for verifying adaptive capture on real modpacks without per-event log spam. */
public final class AdaptiveLogDiagnostics {
    private static final LongAdder ACCEPTED = new LongAdder();
    private static final LongAdder SUPPRESSED = new LongAdder();
    private static final DiagnosticCounters BY_SOURCE = new DiagnosticCounters(256);
    private static final DiagnosticCounters ADAPTER_FAILURES = new DiagnosticCounters(128);

    private AdaptiveLogDiagnostics() {}

    public static void accepted(LogEntry entry) {
        ACCEPTED.increment();
        if (entry == null || entry.source == null || entry.source.isBlank()) return;
        String source = entry.source;
        var kind = CreateContraptionKind.fromSource(source);
        source = switch (kind) {
            case MINECART -> "create:cart"; case TRAIN -> "create:train";
            case BEARING -> "create:bearing"; case OTHER -> "create:contraption"; case UNKNOWN -> source;
        };
        BY_SOURCE.increment(source);
    }

    public static void suppressedDuplicate() {
        SUPPRESSED.increment();
    }

    public static void adapterFailure(String adapterId) {
        String key = adapterId == null || adapterId.isBlank() ? "unknown" : adapterId;
        ADAPTER_FAILURES.increment(key);
    }

    public static void logSummary() {
        String sources = BY_SOURCE.top(12);
        String failures = ADAPTER_FAILURES.top(12);
        AvilixLoggerMod.LOGGER.info(
                "[AvilixLogger] Adaptive capture summary: accepted={}, duplicatesSuppressed={}, sources=[{}], adapterFailures=[{}]",
                ACCEPTED.sum(), SUPPRESSED.sum(), sources, failures);
    }

}
