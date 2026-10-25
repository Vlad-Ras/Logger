package com.roften.avilixlogger.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;

/** Bounded diagnostic cardinality; overflow changes summaries, never the recorded audit events. */
final class DiagnosticCounters {
    private final int limit;
    private final ConcurrentHashMap<String, LongAdder> values = new ConcurrentHashMap<>();
    private final LongAdder other = new LongAdder();
    DiagnosticCounters(int limit) { this.limit = limit; }
    void increment(String key) {
        if (key == null || key.isBlank()) key = "unknown";
        LongAdder counter = values.get(key);
        if (counter == null) {
            synchronized (values) {
                counter = values.get(key);
                if (counter == null && values.size() < limit && key.length() <= 256) {
                    counter = new LongAdder(); values.put(key, counter);
                }
            }
        }
        if (counter == null) other.increment(); else counter.increment();
    }
    int size() { return values.size(); }
    long total() { return other.sum() + values.values().stream().mapToLong(LongAdder::sum).sum(); }
    String top(int limit) {
        String result = values.entrySet().stream()
                .sorted((a,b) -> Long.compare(b.getValue().sum(),a.getValue().sum()))
                .limit(limit).map(e -> e.getKey() + "=" + e.getValue().sum())
                .collect(Collectors.joining(", "));
        return other.sum() == 0 ? result : result + ", [other]=" + other.sum();
    }
}
