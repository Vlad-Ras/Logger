package com.roften.avilixlogger.core;

import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.function.Consumer;

/** Owned exclusively by the event worker. No per-toss timer or shared mutable accumulator. */
final class DropAggregator {
    private record Key(ActionType type, String dim, int x, int y, int z, UUID actor, String actorName,
                       String source, String item, String extra) {}
    private record Pending(LogEntry row, long firstSeenAt, long seenAt, long bytes) {}
    private final LinkedHashMap<Key, Pending> pending = new LinkedHashMap<>();
    private final Consumer<LogEntry> sink;
    private long bytes;
    DropAggregator(Consumer<LogEntry> sink) { this.sink = sink; }
    int pendingCount() { return pending.size(); }

    void add(LogEntry row, long now) {
        Key key = new Key(row.type, row.dim, row.x, row.y, row.z, row.actorUuid, row.actorName,
                row.source, row.itemStackNbt, row.extra);
        Pending previous = pending.remove(key);
        long firstSeenAt = now;
        if (previous != null) {
            bytes -= previous.bytes;
            // Group only a bounded burst at the same location, preserving its first timestamp.
            if (now - previous.firstSeenAt < 250 && Math.abs(row.ts - previous.row.ts) < 250
                    && (long) previous.row.count + row.count <= Integer.MAX_VALUE) {
                row.count += previous.row.count;
                row.ts = Math.min(row.ts, previous.row.ts);
                firstSeenAt = previous.firstSeenAt;
            } else sink.accept(previous.row);
        }
        long weight = PayloadSizeEstimator.estimate(row);
        pending.put(key, new Pending(row, firstSeenAt, now, weight));
        bytes += weight;
        flush(now, false);
    }

    void flush(long now, boolean all) {
        var it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Pending entry = it.next().getValue();
            if (!all && bytes <= 8L * 1024 * 1024
                    && now - entry.seenAt < 250 && now - entry.firstSeenAt < 250) continue;
            sink.accept(entry.row);
            bytes -= entry.bytes;
            it.remove();
        }
    }
}
