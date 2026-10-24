package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import java.util.*;
import java.util.concurrent.*;

/** Deterministic concurrency checks, runnable without a world or a database. */
public final class AsyncPipelineTest {
    public static void main(String[] args) throws Exception {
        PreviewExpiryTest.run();
        LoggerActionLinksTest.run();
        CreateContraptionKindTest.run();
        CaptureTypePolicyTest.run();
        OperatorAccessTest.run();
        HotPathMemoryTest.run();
        HotPathQueueStandaloneTest.run();
        com.roften.avilixlogger.compat.create.CreatePlayerProgressOrderTest.run();
        DetailFormattingTest.run();
        orderedHandoffAndBackpressure();
        oversizedPayload();
        concurrentProducers();
        dropAggregation();
        dropVisibility();
        OptimizationIntegrityTest.run();
        sourceScopeRestoration();
        capturedDeployerAttribution();
        typedCargoAttribution();
        tickDeadlines();
        TickHotPathTest.run();
        CauseContextHotPathTest.run();
        JournalPipelineTest.run();
        preparedSnapshots();
        CartAuditTest.run();
        CreateLinkedTransferPlanTest.run();
        CreateStructuralPlanTest.run();
        CreateCouplingPlanTest.run();
        GuiSearchTest.run();
        ClickHouseDeliveryTest.run();
        System.out.println("Async pipeline checks passed: lossless saturation, ordering, snapshots, shutdown, drops, tick deadlines, journal replay.");
    }

    private static void orderedHandoffAndBackpressure() throws Exception {
        Sink sink = new Sink();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 3, 100_000);
        LogEntry first = row("first"); storage.append(first);
        check(sink.entered.await(3, TimeUnit.SECONDS), "worker never entered backend");
        check(!Thread.currentThread().getName().equals(sink.thread), "backend ran on producer");
        LogEntry second = row("second");
        CompoundTag tag = new CompoundTag(); tag.putString("id", "minecraft:stone");
        second.deferSnapshot(LogEntry.SnapshotField.ITEM, tag);
        storage.append(second); second.extra = "mutated";
        storage.append(row("third"));
        Thread waiting = new Thread(() -> storage.append(row("fourth")), "test-producer");
        waiting.start();
        await(() -> storage.backpressureCount() == 1, "saturated producer did not apply backpressure");
        check(waiting.isAlive(), "full queue must wait, not discard a record");
        sink.release.countDown(); waiting.join(3000); storage.shutdown();
        check(sink.rows.size() == 4, "shutdown failed to drain accepted rows");
        check(sink.rows.get(1).extra.equals("second"), "producer mutated queued record");
        check(sink.rows.get(2).extra.equals("third"), "handoff reordered events");
        check(sink.rows.get(1).itemStackNbt.contains("minecraft:stone"), "deferred NBT was lost");
        check(storage.retainedBytes() == 0, "payload accounting leaked");
        try { storage.append(first); throw new AssertionError("closed queue accepted row"); }
        catch (IllegalStateException expected) {}
    }

    private static void oversizedPayload() {
        Sink sink = new Sink(); sink.release.countDown();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 2, 512);
        LogEntry oversized = row("x".repeat(4096));
        storage.append(oversized); storage.shutdown();
        check(sink.rows.size() == 1, "single large snapshot was discarded");
        check(storage.retainedBytes() == 0, "oversized payload reservation leaked");
    }

    private static void concurrentProducers() throws Exception {
        Sink sink = new Sink(); sink.release.countDown();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 128, 64 * 1024);
        List<Thread> threads = new ArrayList<>();
        long start = System.nanoTime();
        for (int p = 0; p < 4; p++) {
            int producer = p;
            Thread thread = new Thread(() -> {
                for (int i = 0; i < 5000; i++) {
                    LogEntry captured = row(producer + ":" + i);
                    captured.actorUuid = new UUID(0, producer + 1); captured.actorName = "player-" + producer;
                    captured.dim = (i % 2 == 0) ? "overworld" : "nether";
                    captured.x = (i % 2 == 0) ? i : 10000 + i; captured.y = 64; captured.z = producer;
                    storage.append(captured);
                    // The producer moves/changes immediately while the worker processes the queue.
                    captured.actorUuid = null; captured.actorName = "later-player"; captured.dim = "later-dimension";
                    captured.x = -1; captured.y = -1; captured.z = -1;
                }
            });
            threads.add(thread); thread.start();
        }
        for (Thread thread : threads) { thread.join(10_000); check(!thread.isAlive(), "producer deadlocked"); }
        storage.shutdown();
        check(sink.rows.size() == 20_000, "burst lost rows");
        int[] expected = new int[4];
        for (LogEntry row : sink.rows) {
            String[] parts = row.extra.split(":");
            int producer = Integer.parseInt(parts[0]), sequence = Integer.parseInt(parts[1]);
            check(sequence == expected[producer]++, "per-producer order changed");
            check(row.actorUuid.equals(new UUID(0, producer + 1)) && row.actorName.equals("player-" + producer),
                    "concurrent handoff mixed player identities");
            check(row.dim.equals(sequence % 2 == 0 ? "overworld" : "nether")
                    && row.x == (sequence % 2 == 0 ? sequence : 10000 + sequence) && row.y == 64 && row.z == producer,
                    "queued event moved to later teleport coordinates or dimension");
        }
        System.out.printf(java.util.Locale.ROOT, "Synthetic handoff: 20,000 rows / 4 producers in %.1f ms; all delivered%n", (System.nanoTime()-start)/1e6);
    }

    private static void dropAggregation() {
        ArrayList<LogEntry> rows = new ArrayList<>();
        DropAggregator drops = new DropAggregator(rows::add);
        UUID player = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            LogEntry row = row("drop"); row.actorUuid = player; row.dim = "overworld";
            row.type = ActionType.ITEM_DROP; row.count = 1; row.itemStackNbt = "stone";
            drops.add(row, i);
        }
        drops.flush(200, false); check(rows.isEmpty(), "active drop aggregation flushed too early");
        drops.flush(400, false); check(rows.size() == 1 && rows.getFirst().count == 100, "drop count lost or duplicated");
        LogEntry next = row("drop"); next.actorUuid = player; next.dim = "nether"; next.itemStackNbt = "stone"; next.count = 3;
        drops.add(next, 500); drops.flush(500, true);
        check(rows.size() == 2 && rows.get(1).count == 3, "shutdown lost an active drop");
    }

    private static void dropVisibility() throws Exception {
        var rows=new java.util.concurrent.CopyOnWriteArrayList<LogEntry>();
        var sink=new LogStorage(){public void append(LogEntry row){rows.add(row);}public List<LogEntry> query(LogQuery query){return List.copyOf(rows);}public List<LogEntry> queryReverse(LogQuery query){return List.copyOf(rows);}public void shutdown(){}};
        var storage=new AsyncLogStorage(sink,16,1024*1024);
        try{
            var drop=row("pending burst");drop.type=ActionType.ITEM_DROP;drop.count=3;drop.itemStackNbt="stone";drop.aggregateDrop=true;
            storage.append(drop);
            check(storage.awaitVisible(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)),"drop visibility did not drain");
            check(rows.size()==1&&rows.getFirst().count==3,"visibility returned before the aggregated drop reached storage");
        }finally{storage.shutdown();}
    }

    private static void tickDeadlines() {
        ServerTickScheduler.Tasks tasks = new ServerTickScheduler.Tasks();
        List<Integer> seen = new ArrayList<>();
        tasks.add(13, () -> seen.add(3)); tasks.add(11, () -> seen.add(1)); tasks.add(11, () -> seen.add(2));
        tasks.runDue(10); check(seen.isEmpty(), "delayed capture ran in the same tick");
        tasks.runDue(11); check(seen.equals(List.of(1,2)), "tick deadline or FIFO order broken");
        tasks.runDue(12); check(seen.size() == 2, "future capture ran early");
        tasks.runDue(13); check(seen.equals(List.of(1,2,3)), "capture missed its tick");
    }

    private static void preparedSnapshots() {
        var block = new RollbackEngine.PreparedBlockSnapshot(net.minecraft.core.BlockPos.ZERO,
                "{Name:'minecraft:chest'}", "{Items:[]}", "{Size:27,Items:[]}", ActionType.BLOCK_BREAK);
        check(block.stateTag().getString("Name").equals("minecraft:chest"), "block state was not parsed during preparation");
        check(ContainerSlotSnapshot.isValid(block.slotsTag()), "prepared slots lost their structure");
        LogEntry entity = row("entity"); entity.type = ActionType.ENTITY_DEATH; entity.entityNbt = "{id:'minecraft:pig',Health:20f}";
        long retained = entity.prepareForRollback();
        check(retained > 128 && entity.rollbackEntity().getFloat("Health") == 20f, "entity preparation lost NBT");
        check(entity.prepareForRollback() == 0, "prepared entity parsed twice");
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

    private static void capturedDeployerAttribution() {
        Sink sink = new Sink(); sink.release.countDown();
        AsyncLogStorage storage = new AsyncLogStorage(sink, 8, 100_000);
        var outer = new CartAuditContext.Stamp(UUID.randomUUID(), UUID.randomUUID(), "outer-owner", "work", 0);
        var previous = CartAuditContext.enter(outer);
        String source = CreateContraptionKind.TRAIN.source(UUID.randomUUID(), "deployer:mechanism=create:deployer@0,1,2");
        try {
            LogEntry action = row("unknown-owner"); action.type = ActionType.CREATE_DEPLOYER_ACTION; action.source = source;
            storage.append(action);
            storage.append(row("ordinary-outer-action"));
        } finally { CartAuditContext.restore(previous); storage.shutdown(); }
        check(sink.rows.size() == 2, "attribution test lost rows");
        var action = sink.rows.getFirst();
        check(source.equals(action.source), "outer cart replaced deployer construction");
        check(action.actorUuid == null && action.actorName == null, "outer owner assigned to unknown deployer");
        check(sink.rows.get(1).actorUuid==null && outer.source().equals(sink.rows.get(1).source), "ordinary cart attribution regressed");
    }

    private static void typedCargoAttribution() {
        Sink sink=new Sink();sink.release.countDown();AsyncLogStorage storage=new AsyncLogStorage(sink,8,100_000);
        var outer=new CartAuditContext.Stamp(UUID.randomUUID(),UUID.randomUUID(),"outer-owner","work",0);
        var previous=CartAuditContext.enter(outer);
        var sources=new ArrayList<String>();
        try {
            for(var kind:CreateContraptionKind.values())if(kind!=CreateContraptionKind.UNKNOWN){
                var row=row("cargo");row.type=ActionType.CART_CONTENT_CHANGE;
                row.source=kind.source(UUID.randomUUID(),"cargo:mechanism=create:deployer@1,2,3");sources.add(row.source);storage.append(row);
            }
            var machine=row("machine");machine.type=ActionType.CART_TRANSFER;machine.source=outer.source();machine.actorName="SYSTEM";storage.append(machine);
        } finally {CartAuditContext.restore(previous);storage.shutdown();}
        check(sink.rows.size()==sources.size()+1,"typed cargo lost rows");
        for(int i=0;i<sources.size();i++)check(sources.get(i).equals(sink.rows.get(i).source)&&sink.rows.get(i).actorUuid==null,"outer cart replaced cargo construction/owner");
        check(sink.rows.getLast().actorUuid==null&&sink.rows.getLast().actorName.equals("SYSTEM"),"cart owner blamed for a machine transfer");
    }

    static LogEntry row(String extra) {
        LogEntry row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.ITEM_PICKUP; row.extra = extra; return row;
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void await(java.util.function.BooleanSupplier condition, String message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        check(condition.getAsBoolean(), message);
    }

    private static final class Sink implements LogStorage {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final List<LogEntry> rows = new ArrayList<>();
        volatile String thread;
        public void append(LogEntry row) {
            thread = Thread.currentThread().getName(); entered.countDown();
            try { check(release.await(5, TimeUnit.SECONDS), "test backend timed out"); }
            catch (InterruptedException error) { throw new AssertionError(error); }
            rows.add(row);
        }
        public List<LogEntry> query(LogQuery query) { return List.of(); }
        public List<LogEntry> queryReverse(LogQuery query) { return List.of(); }
        public void shutdown() {}
    }
}
