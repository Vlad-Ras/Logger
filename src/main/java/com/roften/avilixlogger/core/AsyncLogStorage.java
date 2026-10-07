package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import java.util.List;

/** Ordered background serialization; saturation applies backpressure without dropping rows. */
public final class AsyncLogStorage implements LogStorage {
    private final LogStorage delegate;
    private final WeightedQueue<QueuedLogEvent> queue;
    private final Thread worker;

    public AsyncLogStorage(LogStorage delegate, int capacity, long maxBytes) {
        this.delegate = delegate;
        this.queue = new WeightedQueue<>(capacity, maxBytes);
        worker = new Thread(this::process, "avilixlogger-event-worker");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    @Override public void append(LogEntry entry) {
        if (entry == null) return;
        if (CartAuditContext.restoring()) return;
        CartAuditContext.enrich(entry);
        entry.attachCartItemIdentity();
        if (entry.source != null && entry.source.startsWith("create:cart:")) LogIdGenerator.ensure(entry);
        LogEntry snapshot = entry.copyForQueue();
        queue.put(snapshot, PayloadSizeEstimator.estimate(snapshot));
    }

    private record BlockChangeEvent(SetBlockCapture before,
                                    net.minecraft.world.level.block.state.BlockState after,
                                    String afterId, long timestamp) implements QueuedLogEvent {
        @Override public LogEntry resolve() { return before.entry(after, afterId, timestamp); }
    }

    /** No LogEntry allocation/copy or text encoding on the producer's block-mutation path. */
    public void appendBlockChange(SetBlockCapture before,
                                  net.minecraft.world.level.block.state.BlockState after,
                                  String afterId, long timestamp) {
        appendCaptured(new BlockChangeEvent(before, after, afterId, timestamp),
                PayloadSizeEstimator.estimateBlockChange(before, afterId));
    }

    void appendCaptured(QueuedLogEvent event, long bytes) { queue.put(event, bytes); }

    private void process() {
        DropAggregator drops = new DropAggregator(delegate::append);
        long nextMaintenance = 0;
        while (!queue.exhausted()) {
            try {
                var work = queue.poll(250);
                long now = System.currentTimeMillis();
                drops.flush(now, false);
                if (now >= nextMaintenance) {
                    LoggerEventHandlers.cleanupBackground(now);
                    nextMaintenance = now + 1000;
                }
                if (work == null) continue;
                try {
                    LogEntry row = work.value().resolve();
                    row.materializeSnapshots();
                    if (row.aggregateDrop) drops.add(row, now);
                    else delegate.append(row);
                } catch (Throwable error) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Event processing failed", error);
                } finally { queue.complete(work); }
            } catch (InterruptedException ignored) {
                // Closing the queue wakes this worker; accepted events are always drained.
            }
        }
        drops.flush(System.currentTimeMillis(), true);
    }

    @Override public List<LogEntry> query(LogQuery query) { return delegate.query(query); }
    @Override public List<LogEntry> queryReverse(LogQuery query) { return delegate.queryReverse(query); }

    @Override public boolean awaitVisible(long deadline) throws InterruptedException { return queue.awaitEmpty(deadline) && delegate.awaitVisible(deadline); }

    @Override public void shutdown() {
        queue.close();
        WeightedQueue.join(worker);
        if (queue.backpressureCount() > 0) AvilixLoggerMod.LOGGER.warn(
                "[AvilixLogger] Event queue applied backpressure {} times; no overflow rows were discarded",
                queue.backpressureCount());
    }

    long backpressureCount() { return queue.backpressureCount(); }
    long retainedBytes() { return queue.retainedBytes(); }
}
