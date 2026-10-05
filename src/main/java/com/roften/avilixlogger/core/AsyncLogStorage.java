package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Ordered, bounded handoff. No adapter, deduplication or backend runs on the producer. */
public final class AsyncLogStorage implements LogStorage {
    private record Pending(LogEntry entry, long bytes) {}
    private final LogStorage delegate;
    private final ArrayBlockingQueue<Pending> queue;
    private final long maxBytes;
    private final AtomicLong retainedBytes = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    private final Thread worker;
    private volatile boolean accepting = true;

    public AsyncLogStorage(LogStorage delegate, int capacity, long maxBytes) {
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.maxBytes = maxBytes;
        worker = new Thread(this::process, "avilixlogger-event-worker");
        worker.setDaemon(true);
        worker.setPriority(Thread.NORM_PRIORITY - 1);
        worker.start();
    }

    @Override public synchronized void append(LogEntry entry) {
        if (entry == null || !accepting) return;
        // Ownership transfers here. Callers may reuse/mutate their original LogEntry afterwards.
        LogEntry snapshot = entry.copyForQueue();
        long bytes = PayloadSizeEstimator.estimate(snapshot);
        if (!reserve(bytes)) { reject(); return; }
        Pending pending = new Pending(snapshot, bytes);
        if (!accepting || !queue.offer(pending)) {
            retainedBytes.addAndGet(-bytes);
            reject();
        }
    }

    private boolean reserve(long bytes) {
        while (true) {
            long retained = retainedBytes.get();
            if (bytes > maxBytes || retained > maxBytes - bytes) return false;
            if (retainedBytes.compareAndSet(retained, retained + bytes)) return true;
        }
    }

    private void reject() { dropped.incrementAndGet(); }

    private void process() {
        long reported = 0;
        try {
            while (accepting || !queue.isEmpty()) {
                Pending pending = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    try {
                        pending.entry.materializeSnapshots();
                        delegate.append(pending.entry);
                    } catch (Throwable error) {
                        AvilixLoggerMod.LOGGER.error("[AvilixLogger] Event processing failed", error);
                    } finally { retainedBytes.addAndGet(-pending.bytes); }
                }
                long lost = dropped.get();
                if (lost != reported && (reported == 0 || lost - reported >= 1000 || !accepting)) {
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Event queue rejected {} rows; queue={}, retainedMiB={}",
                            lost, queue.size(), retainedBytes.get() / (1024L * 1024L));
                    reported = lost;
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
        }
    }

    @Override public List<LogEntry> query(LogQuery query) { return delegate.query(query); }
    @Override public List<LogEntry> queryReverse(LogQuery query) { return delegate.queryReverse(query); }

    /** Stops intake, then drains accepted entries before downstream storage is shut down. */
    @Override public void shutdown() {
        synchronized (this) { accepting = false; }
        try { worker.join(5000); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (worker.isAlive()) {
            AvilixLoggerMod.LOGGER.error("[AvilixLogger] Event worker did not drain within shutdown deadline; {} queued rows remain",
                    queue.size());
            worker.interrupt();
        }
    }

    long droppedRows() { return dropped.get(); }
    long retainedBytes() { return retainedBytes.get(); }
}
