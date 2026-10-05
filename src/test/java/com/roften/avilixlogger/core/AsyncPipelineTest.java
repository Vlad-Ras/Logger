package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Deterministic concurrency checks, runnable without a world or a database. */
public final class AsyncPipelineTest {
    public static void main(String[] args) throws Exception {
        orderedNonBlockingHandoff();
        payloadBudget();
        sourceScopeRestoration();
        System.out.println("Async pipeline checks passed: handoff, order, snapshots, bounds, shutdown, source scopes.");
    }

    private static void orderedNonBlockingHandoff() throws Exception {
        Sink sink = new Sink();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 2, 100_000);
        LogEntry first = new LogEntry(); first.extra = "first";
        storage.append(first);
        check(sink.entered.await(2, TimeUnit.SECONDS), "worker never entered backend");
        check(!Thread.currentThread().getName().equals(sink.thread), "backend ran on producer");

        LogEntry second = new LogEntry(); second.extra = "second";
        CompoundTag tag = new CompoundTag(); tag.putString("id", "minecraft:stone");
        second.deferSnapshot(LogEntry.SnapshotField.ITEM, tag);
        storage.append(second); second.extra = "mutated";
        LogEntry third = new LogEntry(); third.extra = "third"; storage.append(third);
        // The backend remains blocked. This must reject immediately instead of running on caller.
        storage.append(new LogEntry());
        check(storage.droppedRows() == 1, "full queue was not rejected");
        sink.release.countDown(); storage.shutdown();
        check(sink.rows.size() == 3, "shutdown failed to drain accepted rows");
        check(sink.rows.get(1).extra.equals("second"), "producer mutated queued record");
        check(sink.rows.get(2).extra.equals("third"), "handoff reordered events");
        check(sink.rows.get(1).itemStackNbt.contains("minecraft:stone"), "deferred NBT was lost");
        check(storage.retainedBytes() == 0, "payload accounting leaked");
        storage.append(first);
        check(sink.rows.size() == 3, "shutdown accepted new event");
    }

    private static void payloadBudget() {
        Sink sink = new Sink(); sink.release.countDown();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 2, 512);
        LogEntry oversized = new LogEntry(); oversized.extra = "x".repeat(4096);
        storage.append(oversized);
        check(storage.droppedRows() == 1, "oversized payload accepted");
        check(storage.retainedBytes() == 0, "rejection leaked payload reservation");
        storage.shutdown();
    }

    private static void sourceScopeRestoration() {
        check(MutationSourceResolver.resolveExternalSource() == null, "unexpected initial context");
        try (var outer = MutationSourceResolver.push("mod:create")) {
            try (var inner = MutationSourceResolver.push("mod:ae2")) {
                check(MutationSourceResolver.resolveExternalSource().equals("mod:ae2"), "nested source lost");
                throw new IllegalStateException("simulated tick failure");
            } catch (IllegalStateException expected) {}
            check(MutationSourceResolver.resolveExternalSource().equals("mod:create"), "exception leaked source");
        }
        check(MutationSourceResolver.resolveExternalSource() == null, "source leaked into next tick");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static final class Sink implements LogStorage {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<LogEntry> rows = new ArrayList<>();
        volatile String thread;
        public void append(LogEntry row) {
            thread = Thread.currentThread().getName(); entered.countDown();
            try { check(release.await(3, TimeUnit.SECONDS), "test backend timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            rows.add(row);
        }
        public List<LogEntry> query(LogQuery query) { return List.of(); }
        public List<LogEntry> queryReverse(LogQuery query) { return List.of(); }
        public void shutdown() {}
    }
}
