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
    private static volatile ThreadPoolExecutor executor;

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
                    (r, pool) -> {
                        long n = REJECTED.incrementAndGet();
                        if (n == 1 || n % 1000 == 0) {
                            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Async CPU queue is full; rejected {} expensive post-processing tasks. Increase async.queueCapacity/workerThreads.", n);
                        }
                    }
            );
            ex.prestartAllCoreThreads();
            executor = ex;
            return ex;
        }
    }

    public static void submit(Runnable task) {
        if (task == null) return;
        try {
            executor().execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Async log post-processing failed", t);
                }
            });
        } catch (Throwable t) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Cannot submit async log task", t);
        }
    }

    public static void shutdown() {
        ThreadPoolExecutor ex = executor;
        executor = null;
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
    }
}
