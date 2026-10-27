package com.roften.avilixlogger.core;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Background journal and delivery. DB downtime does not retain the backlog on the JVM heap. */
public final class PendingLogStorage implements LogStorage {
    private final WeightedQueue<LogEntry> queue;
    private final DiskLogJournal journal;
    private final int batchSize;
    private final long flushMillis;
    private final Thread journalWriter;
    private final Thread sender;
    private final Object wakeup = new Object();
    private long wakeupVersion;
    private volatile BatchLogStorage delegate;
    private volatile boolean initialized;
    private volatile boolean sending = true;
    private volatile Throwable journalFailure;

    public PendingLogStorage() {
        this(configuredDirectory(), LoggerConfig.VALUES.clickHouseQueueCapacity.get(),
                LoggerConfig.journalQueueBytes(),
                LoggerConfig.VALUES.clickHouseBatchSize.get(), LoggerConfig.VALUES.clickHouseFlushIntervalMs.get());
    }

    PendingLogStorage(Path directory, int capacity, long bytes, int batchSize, long flushMillis) {
        queue = new WeightedQueue<>(capacity, bytes);
        journal = new DiskLogJournal(directory);
        this.batchSize = Math.max(1, batchSize);
        this.flushMillis = Math.max(1, flushMillis);
        journalWriter = worker(this::writeJournal, "avilixlogger-journal");
        sender = worker(this::sendJournal, "avilixlogger-delivery");
        journalWriter.start();
        sender.start();
    }

    private static Path configuredDirectory() {
        String destination = LoggerConfig.VALUES.clickHouseUrl.get() + "\n" + LoggerConfig.VALUES.clickHouseDatabase.get()
                + "\n" + LoggerConfig.VALUES.clickHouseTablePrefix.get() + "\n" + LoggerConfig.VALUES.clickHouseTable.get()
                + "\n" + LoggerConfig.VALUES.clickHouseSchemaMode.get();
        String key = UUID.nameUUIDFromBytes(destination.getBytes(StandardCharsets.UTF_8)).toString();
        return net.neoforged.fml.loading.FMLPaths.GAMEDIR.get().resolve("avilixlogger-spool").resolve(key);
    }

    private static Thread worker(Runnable task, String name) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    }

    @Override public void append(LogEntry entry) {
        if (entry == null) return;
        LogIdGenerator.ensure(entry);
        queue.put(entry, PayloadSizeEstimator.estimate(entry));
    }

    private void writeJournal() {
        while (!initialized) {
            try { journal.initialize(); initialized = true; signal(); }
            catch (Exception error) {
                if (queue.exhausted() && queue.retainedBytes() == 0) return;
                reportJournalError(error); pause(1000);
            }
        }
        ArrayList<WeightedQueue.Work<LogEntry>> batch = new ArrayList<>(Math.min(batchSize, 10_000));
        long started = 0;
        long bytes = 0;
        while (!queue.exhausted() || !batch.isEmpty()) {
            try {
                long elapsed = batch.isEmpty() ? 0 : java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                var work = batch.isEmpty() ? queue.poll(flushMillis) : queue.pollForBatch(Math.max(0, flushMillis - elapsed));
                if (work != null) {
                    if (batch.isEmpty()) started = System.nanoTime();
                    batch.add(work);
                    bytes += work.bytes();
                }
                if (!batch.isEmpty() && (batch.size() >= batchSize || bytes >= 4L * 1024 * 1024
                        || java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) >= flushMillis
                        || (work == null && queue.hasWaitingProducers()) || queue.exhausted())) {
                    List<LogEntry> rows = batch.stream().map(WeightedQueue.Work::value).toList();
                    // Retry this exact batch on disk failure; never release its reservations early.
                    String token = journal.newToken();
                    while (true) {
                        try { journal.write(token, rows); journalFailure = null; break; }
                        catch (Exception error) { reportJournalError(error); pause(1000); }
                    }
                    for (var accepted : batch) queue.complete(accepted);
                    batch.clear(); bytes = 0;
                    signal();
                }
            } catch (InterruptedException ignored) {}
        }
    }

    private void sendJournal() {
        long retryMillis = 250;
        while (sending) {
            long observed = wakeupVersion();
            BatchLogStorage current = delegate;
            if (!initialized || current == null) { pauseIfUnchanged(250, observed); continue; }
            try {
                List<Path> pending = journal.pending(64);
                if (pending.isEmpty()) { pauseIfUnchanged(250, observed); continue; }
                for (Path file : pending) {
                    if (!sending) break;
                    List<LogEntry> rows = DiskLogJournal.read(file);
                    current.appendBatch(file.getFileName().toString(), rows);
                    DiskLogJournal.acknowledge(file);
                    retryMillis = 250;
                }
            } catch (Exception failure) {
                AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Delivery delayed; journal retained for retry: {}", failure.toString());
                retryPause(retryMillis, current);
                retryMillis = delegate != current ? 250 : Math.min(10_000, retryMillis * 2);
            }
        }
    }

    private void reportJournalError(Throwable failure) {
        if (journalFailure == null) AvilixLoggerMod.LOGGER.error(
                "[AvilixLogger] Cannot persist journal; retaining accepted rows and applying backpressure", failure);
        journalFailure = failure;
    }

    private void signal() { synchronized (wakeup) { wakeupVersion++; wakeup.notifyAll(); } }
    private long wakeupVersion() { synchronized (wakeup) { return wakeupVersion; } }
    private void pauseIfUnchanged(long millis, long observed) {
        synchronized (wakeup) {
            if (!sending || wakeupVersion != observed) return;
            try { wakeup.wait(millis); } catch (InterruptedException ignored) {}
        }
    }
    /** New journal batches must not turn an unavailable DB into a retry/log storm. */
    private void retryPause(long millis, BatchLogStorage failed) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
        synchronized (wakeup) {
            while (sending && delegate == failed) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return;
                try { java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(wakeup, remaining); }
                catch (InterruptedException ignored) {}
            }
        }
    }
    private void pause(long millis) {
        synchronized (wakeup) {
            try { wakeup.wait(millis); } catch (InterruptedException ignored) {}
        }
    }

    @Override public List<LogEntry> query(LogQuery query) { return requireDelegate().query(query); }
    @Override public List<LogEntry> queryReverse(LogQuery query) { return requireDelegate().queryReverse(query); }
    private LogStorage requireDelegate() {
        LogStorage current = delegate;
        if (current == null) throw new IllegalStateException("ClickHouse storage is still initializing");
        return current;
    }

    @Override public boolean awaitVisible(long deadline) throws InterruptedException {
        if (!queue.awaitEmpty(deadline)) return false;
        while (System.nanoTime() < deadline) {
            try { if (initialized && delegate != null && !journal.hasPending()) return true; }
            catch (java.io.IOException e) { throw new IllegalStateException("Журнал недоступен",e); }
            Thread.sleep(25);
        }
        return false;
    }

    public void setDelegate(BatchLogStorage storage) { delegate = storage; signal(); }

    @Override public void shutdown() {
        queue.close(); signal();
        WeightedQueue.join(journalWriter);
        // No need to wait for a DB outage: all accepted rows now reside in published files.
        sending = false; signal();
        WeightedQueue.join(sender);
    }

    long retainedBytes() { return queue.retainedBytes(); }
}
