package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

final class Ae2TransferDetailsTest {
    static LogEntry record(String kind, long amount) {
        var row = AsyncPipelineTest.row("ae2-transfer-test");
        row.type = ActionType.ME_PUT; row.source = "ae2:InterfaceBlockEntity"; row.dim = "minecraft:overworld";
        row.x = -23; row.y = 91; row.z = 42;
        var key = new CompoundTag(); key.putString("id", kind.equals("i") ? "minecraft:diamond" : kind.equals("f") ? "minecraft:water" : "example:steam");
        var components = new CompoundTag(); components.putString("minecraft:custom_name", "exact component " + "v".repeat(3000)); key.put("components", components);
        Ae2TransferDetails.attach(row, kind, key.getString("id"), kind.equals("i") ? "ae2:item" : kind.equals("f") ? "ae2:fluid" : "example:gas", amount, kind.equals("i") ? "" : kind.equals("f") ? "B" : "kU", kind.equals("i") ? 1 : kind.equals("f") ? 1000 : 10000, key);
        if (kind.equals("i")) { row.count = (int)Math.min(Integer.MAX_VALUE, amount); row.deferSnapshot(LogEntry.SnapshotField.ITEM, key.copy()); }
        return row;
    }
    static void run() throws Exception {
        var item = record("i", Long.MAX_VALUE); item.materializeSnapshots();
        check(Ae2TransferDetails.summary(item).amount() == Long.MAX_VALUE && Ae2TransferDetails.context(item).getLong("Amount") == Long.MAX_VALUE, "ME amount truncated to int/double");
        check(Ae2TransferDetails.summary(item).amountLabel().contains("x" + Long.MAX_VALUE), "ME line used saturated int count");
        var fluid = record("f", 1234567); fluid.materializeSnapshots();
        check(Ae2TransferDetails.summary(fluid).amountLabel().contains("1234567 мБ"), "fluid amount missing from feed label");
        var generic = record("g", 99); generic.materializeSnapshots();
        check(Ae2TransferDetails.summary(generic).amountLabel().contains("99 ед.") && !Ae2TransferDetails.summary(generic).amountLabel().contains("мБ"), "third-party key claimed fluid units");
        var old = row("legacy ME"); old.type = ActionType.ME_TAKE; old.extra = "старый ресурс 100 mB";
        check(Ae2TransferDetails.summary(old) == null && Ae2TransferDetails.describe(old).isEmpty(), "legacy extra reinterpreted as a new key");
        for (String value : List.of("ae2-transfer-v1|f|minecraft:water|ae2:fluid|-1", "ae2-transfer-v1|f|minecraft:water|ae2:fluid|9223372036854775808", "ae2-transfer-v1|f|bad id|ae2:fluid|1")) {
            var bad = fluid.copyForQueue(); bad.extra = value; check(Ae2TransferDetails.summary(bad) == null, "invalid ME amount/resource accepted");
        }
        var mismatch = fluid.copyForQueue(); mismatch.beAfter = fluid.beAfter.replace("1234567L", "1234568L");
        check(Ae2TransferDetails.context(mismatch) == null, "mismatching summary and full evidence accepted");
        var rows = new CopyOnWriteArrayList<LogEntry>();
        var sink = new LogStorage() {
            public void append(LogEntry row) { rows.add(row); }
            public List<LogEntry> query(LogQuery query) { return List.copyOf(rows); }
            public List<LogEntry> queryReverse(LogQuery query) { return query(query); }
            public void shutdown() {}
        };
        var storage = new AsyncLogStorage(sink, 4, 1024 * 1024);
        var pending = record("f", Long.MAX_VALUE); var tag = (CompoundTag) pending.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
        var encodes = new AtomicInteger();
        var probe = new CompoundTag() {
            @Override public String toString() { check(Thread.currentThread().getName().equals("avilixlogger-event-worker"), "ME SNBT encoded on producer"); encodes.incrementAndGet(); return super.toString(); }
        };
        probe.merge(tag); pending.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, probe);
        check(PayloadSizeEstimator.estimate(pending) >= tag.sizeInBytes() * 2L, "full resource key absent from byte accounting");
        storage.append(pending); pending.extra = "producer mutation"; storage.shutdown();
        check(rows.size() == 1 && encodes.get() == 1 && Ae2TransferDetails.context(rows.getFirst()).getCompound("Key").equals(tag.getCompound("Key")), "worker lost captured ME payload");
        var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(rows.getFirst()), LogEntry.class);
        check(replay.beAfter.equals(rows.getFirst().beAfter) && Ae2TransferDetails.summary(replay).amount() == Long.MAX_VALUE, "Gzip replay changed ME long/key");
        var directory = java.nio.file.Files.createTempDirectory("ae2-transfer-journal-");
        try {
            var journal = new DiskLogJournal(directory); journal.initialize(); LogIdGenerator.ensure(replay);
            var file = journal.write(journal.newToken(), List.of(replay)); var fromDisk = DiskLogJournal.read(file).getFirst();
            check(fromDisk.id == replay.id && fromDisk.beAfter.equals(replay.beAfter) && Ae2TransferDetails.summary(fromDisk).amount() == Long.MAX_VALUE, "disk journal changed ME key/quantity/ID");
        } finally { try (var files = java.nio.file.Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path); } }
        System.out.println("AE2_TRANSFER_EVIDENCE_OK: exact long, item/fluid/generic units, legacy/malformed refusal, bounded weight, worker-only SNBT and full key replay");
    }
}
