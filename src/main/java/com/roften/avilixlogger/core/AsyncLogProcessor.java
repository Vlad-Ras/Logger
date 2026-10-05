package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CPU/background processor for expensive log post-processing.
 *
 * The Minecraft server thread may only capture already-safe snapshots. Heavy parsing,
 * aggregated container diffs and LogEntry fan-out must happen here, otherwise 40+ online
 * players can turn container close / modded BE interactions into long tick spikes.
 */
public final class AsyncLogProcessor {
    private static final AtomicInteger THREAD_ID = new AtomicInteger(1);
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong REPORTED = new AtomicLong();
    private static final AtomicLong QUEUED_PAYLOAD_BYTES = new AtomicLong();
    private static volatile ThreadPoolExecutor executor;
    private static volatile boolean stopping;

    private AsyncLogProcessor() {}

    private static ThreadPoolExecutor executor() {
        ThreadPoolExecutor ex = executor;
        if (ex != null) return ex;
        synchronized (AsyncLogProcessor.class) {
            ex = executor;
            if (ex != null) return ex;

            int threads;
            int queueCapacity;
            try {
                threads = Math.max(1, LoggerConfig.VALUES.asyncWorkerThreads.get());
                queueCapacity = Math.max(10_000, LoggerConfig.VALUES.asyncQueueCapacity.get());
            } catch (Throwable ignored) {
                threads = 2;
                queueCapacity = 100_000;
            }

            ThreadFactory tf = r -> {
                Thread t = new Thread(r, "avilixlogger-cpu-worker-" + THREAD_ID.getAndIncrement());
                t.setDaemon(true);
                t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                return t;
            };

            ex = new ThreadPoolExecutor(
                    threads,
                    threads,
                    0L,
                    TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    tf,
                    new ThreadPoolExecutor.AbortPolicy()
            );
            ex.prestartAllCoreThreads();
            executor = ex;
            return ex;
        }
    }

    public static void submit(Runnable task) {
        submit(1_024L, task);
    }

    public static void submit(long estimatedPayloadBytes, Runnable task) {
        if (task == null || stopping || !LoggerConfig.isEnabled()) return;
        long weight = Math.max(128L, estimatedPayloadBytes);
        if (!reservePayload(weight)) {
            onRejected("payload budget");
            return;
        }
        try {
            executor().execute(() -> {
                try {
                    if (!LoggerConfig.isEnabled()) return;
                    task.run();
                } catch (Throwable t) {
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Async log post-processing failed", t);
                } finally {
                    QUEUED_PAYLOAD_BYTES.addAndGet(-weight);
                    reportRejected();
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            QUEUED_PAYLOAD_BYTES.addAndGet(-weight);
            onRejected("task capacity");
        } catch (Throwable t) {
            QUEUED_PAYLOAD_BYTES.addAndGet(-weight);
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Cannot submit async log task", t);
        }
    }

    private static boolean reservePayload(long bytes) {
        long maxBytes;
        try {
            maxBytes = Math.max(16L, LoggerConfig.VALUES.asyncMaxQueuedPayloadMiB.get()) * 1024L * 1024L;
        } catch (Throwable ignored) {
            maxBytes = 128L * 1024L * 1024L;
        }
        while (true) {
            long current = QUEUED_PAYLOAD_BYTES.get();
            if (bytes > maxBytes || current > maxBytes - bytes) return false;
            if (QUEUED_PAYLOAD_BYTES.compareAndSet(current, current + bytes)) return true;
        }
    }

    private static void onRejected(String reason) {
        REJECTED.incrementAndGet();
    }

    private static void reportRejected() {
        long count = REJECTED.get();
        long reported = REPORTED.get();
        if (count > reported && (reported == 0 || count - reported >= 1000)
                && REPORTED.compareAndSet(reported, count)) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Async CPU queue rejected {} tasks; queuedTasks={}, estimatedPayloadMiB={}",
                    count, executor == null ? 0 : executor.getQueue().size(),
                    QUEUED_PAYLOAD_BYTES.get() / (1024L * 1024L));
        }
    }

    public static void shutdown() {
        stopping = true;
        reportRejected();
        ThreadPoolExecutor ex = executor;
        if (ex == null) return;
        ex.shutdown();
        try {
            if (!ex.awaitTermination(5, TimeUnit.SECONDS)) {
                ex.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            ex.shutdownNow();
        }
        if (ex.isTerminated()) {
            executor = null;
            QUEUED_PAYLOAD_BYTES.set(0L);
        }
    }

    public static synchronized void warmup() {
        if (executor != null && executor.isShutdown() && !executor.isTerminated())
            throw new IllegalStateException("Previous CPU workers have not stopped");
        if (executor != null && executor.isTerminated()) executor = null;
        stopping = false;
        executor();
    }
}
