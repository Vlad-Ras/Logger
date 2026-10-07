package com.roften.avilixlogger.core;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;

/** Owned exclusively by the event worker. No per-toss timer or shared mutable accumulator. */
final class DropAggregator {
    private record Key(String dim, UUID actor, String item) {}
    private record Pending(LogEntry row, long seenAt, long bytes) {}
    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private final Consumer<LogEntry> sink;
    private long bytes;
    DropAggregator(Consumer<LogEntry> sink) { this.sink = sink; }

    void add(LogEntry row, long now) {
        Key key = new Key(row.dim, row.actorUuid, row.itemStackNbt);
        Pending previous = pending.remove(key);
        if (previous != null) {
            bytes -= previous.bytes;
            if ((long) previous.row.count + row.count <= Integer.MAX_VALUE) row.count += previous.row.count;
            else sink.accept(previous.row);
        }
        long weight = PayloadSizeEstimator.estimate(row);
        pending.put(key, new Pending(row, now, weight));
        bytes += weight;
        flush(now, false);
    }

    void flush(long now, boolean all) {
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending entry = it.next().getValue();
            if (!all && bytes <= 8L * 1024 * 1024 && now - entry.seenAt < 250) break;
            sink.accept(entry.row);
            bytes -= entry.bytes;
            it.remove();
        }
    }
}
