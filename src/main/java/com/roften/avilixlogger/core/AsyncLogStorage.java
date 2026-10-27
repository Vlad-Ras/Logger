package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import java.util.List;

/** Ordered background serialization; saturation applies backpressure without dropping rows. */
public final class AsyncLogStorage implements LogStorage {
    private final LogStorage delegate;
    private final WeightedQueue<QueuedLogEvent> queue;
    private final Thread worker;
    private volatile int pendingDrops;

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
        // Decide before enriching, deep-copying or enqueuing a row.
        if (!CaptureTypePolicy.allows(entry.type,entry.source,entry.extra)) return;
        // Deployer capture already resolved its own owner; null explicitly means unknown.
        if (entry.type != ActionType.CREATE_DEPLOYER_ACTION) CartAuditContext.enrich(entry);
        String constructionSource = MutationSourceResolver.resolveExternalSource();
        if (CreateContraptionKind.fromSource(entry.source) == CreateContraptionKind.UNKNOWN
                && CreateContraptionKind.id(constructionSource) != null) entry.source = constructionSource;
        entry.source = CreateMechanismContext.decorate(entry.source);
        // The current CartAuditContext may have supplied the definitive source.
        // Recheck before item-identity snapshots to prevent typed-source bypasses.
        if (!CaptureTypePolicy.allows(entry.type,entry.source,entry.extra)) return;
        entry.attachCartItemIdentity();
        if (entry.source != null && entry.source.startsWith("create:cart:")) LogIdGenerator.ensure(entry);
        LogEntry snapshot = entry.copyForQueue();
        ActionType capturedType=snapshot.type;
        String capturedSource=snapshot.source;
        queue.put(snapshot, PayloadSizeEstimator.estimate(snapshot));
        HotPathMetrics.queued(capturedType,capturedSource);
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
        if (!CaptureTypePolicy.allows(ActionType.BLOCK_PLACE,before.source(),null)) return;
        appendCaptured(new BlockChangeEvent(before, after, afterId, timestamp),
                PayloadSizeEstimator.estimateBlockChange(before, afterId));
        HotPathMetrics.queued(SetBlockCapture.classify(before.beforeState().isAir(),after.isAir(),
                before.beforeState()!=after,before.causeKind()),before.source());
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
                pendingDrops=drops.pendingCount();
                if (now >= nextMaintenance) {
                    LoggerEventHandlers.cleanupBackground(now);
                    nextMaintenance = now + 1000;
                }
                if (work == null) continue;
                try {
                    LogEntry row = work.value().resolve();
                    if (row == null) continue;
                    row.materializeSnapshots();
                    if (row.aggregateDrop) drops.add(row, now);
                    else delegate.append(row);
                } catch (Throwable error) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Event processing failed", error);
                } finally { pendingDrops=drops.pendingCount();queue.complete(work); }
            } catch (InterruptedException ignored) {
                // Closing the queue wakes this worker; accepted events are always drained.
            }
        }
        drops.flush(System.currentTimeMillis(), true);
        pendingDrops=0;
    }

    @Override public List<LogEntry> query(LogQuery query) { return delegate.query(query); }
    @Override public List<LogEntry> queryReverse(LogQuery query) { return delegate.queryReverse(query); }

    @Override public boolean awaitVisible(long deadline) throws InterruptedException {
        while(queue.awaitEmpty(deadline)){
            // Queue completion alone does not publish an active aggregation burst.
            // The worker's normal 250 ms flush handles it; callers wait off-thread.
            if(pendingDrops==0)return delegate.awaitVisible(deadline);
            long remaining=deadline-System.nanoTime();if(remaining<=0)return false;
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(Math.min(remaining,10_000_000L));
        }
        return false;
    }

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
