package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

/** CPU workers only receive detached snapshots. No fallback executes on the world thread. */
public final class AsyncLogProcessor {
    private static final ThreadLocal<Boolean> WORKER = ThreadLocal.withInitial(() -> false);
    private static volatile Pool pool;
    private static volatile boolean stopping;
    private AsyncLogProcessor() {}

    private static Pool pool() {
        Pool current = pool;
        if (current != null) return current;
        synchronized (AsyncLogProcessor.class) {
            if (pool == null) {
                if (stopping) throw new IllegalStateException("Logger CPU workers stopped");
                pool = new Pool(LoggerConfig.VALUES.asyncWorkerThreads.get(),
                        LoggerConfig.VALUES.asyncQueueCapacity.get(),
                        LoggerConfig.VALUES.asyncMaxQueuedPayloadMiB.get() * 1024L * 1024L);
            }
            return pool;
        }
    }

    public static void submit(Runnable task) { submit(1024L, task); }

    public static void submit(long bytes, Runnable task) {
        if (task == null) return;
        // Nested work is already off the world thread; avoid a full-queue self-deadlock.
        if (WORKER.get()) { task.run(); return; }
        pool().queue.put(task, Math.max(128L, bytes));
    }

    public static void shutdown() {
        stopping = true;
        Pool current = pool;
        if (current == null) return;
        current.queue.close();
        for (Thread worker : current.workers) WeightedQueue.join(worker);
        if (current.queue.backpressureCount() > 0) AvilixLoggerMod.LOGGER.warn(
                "[AvilixLogger] CPU queue applied backpressure {} times; no overflow tasks were discarded",
                current.queue.backpressureCount());
        pool = null;
    }

    public static synchronized void warmup() {
        stopping = false;
        pool();
    }

    private static final class Pool {
        final WeightedQueue<Runnable> queue;
        final Thread[] workers;
        Pool(int count, int capacity, long bytes) {
            queue = new WeightedQueue<>(capacity, bytes);
            workers = new Thread[Math.max(1, count)];
            for (int i = 0; i < workers.length; i++) {
                workers[i] = new Thread(this::run, "avilixlogger-cpu-worker-" + (i + 1));
                workers[i].setDaemon(true);
                workers[i].setPriority(Thread.NORM_PRIORITY - 1);
                workers[i].start();
            }
        }
        void run() {
            WORKER.set(true);
            try {
                while (!queue.exhausted()) {
                    try {
                        var work = queue.poll(1000);
                        if (work == null) continue;
                        try { work.value().run(); }
                        catch (Throwable error) { AvilixLoggerMod.LOGGER.error("[AvilixLogger] Snapshot processing failed", error); }
                        finally { queue.complete(work); }
                    } catch (InterruptedException ignored) {}
                }
            } finally { WORKER.remove(); }
        }
    }
}
