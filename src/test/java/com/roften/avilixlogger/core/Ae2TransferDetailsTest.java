package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

final class Ae2TransferDetailsTest {
    static LogEntry device() {
        var row = record("i", 3); row.type = ActionType.ME_TAKE;
        row.source = "ae2:PatternProviderMenu:ENCODED_PATTERN:slot=36";
        var item = new CompoundTag(); item.putString("id", "ae2:processing_pattern"); item.putInt("count", 3);
        var components = new CompoundTag(); components.putString("test:data", "native pattern " + "x".repeat(3000)); item.put("components", components);
        Ae2TransferDetails.device(row, "ae2:processing_pattern", 3, item); row.count = 3; return row;
    }
    static LogEntry remote() {
        var row = device(); row.source = "ae2:PatternAccessTermMenu:terminal=minecraft:overworld@180,100,180:provider=-9223372036854775807:ENCODED_PATTERN:slot=7";
        row.actorUuid = UUID.randomUUID(); row.actorName = "RemotePatternOwner"; return row;
    }
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
        var device = device(); device.materializeSnapshots();
        check(Ae2TransferDetails.context(device).getString("Domain").equals("device-slot") && device.itemStackNbt.length() > 3000
                && Ae2TransferDetails.summary(device).amount() == 3 && Ae2TransferDetails.describe(device).stream().anyMatch(c -> c.getString().contains("физический слот")), "device transfer lost domain/components/delta");
        var invalidDevice = device.copyForQueue(); invalidDevice.beAfter = device.beAfter.replace("device-slot", "unknown");
        check(Ae2TransferDetails.context(invalidDevice) == null, "unknown device domain accepted");
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
        storage.append(pending); pending.extra = "producer mutation";
        var pendingDevice = device(); long deviceWeight = 0;
        for (var field : List.of(LogEntry.SnapshotField.ITEM, LogEntry.SnapshotField.BE_AFTER)) {
            var source = (CompoundTag)pendingDevice.copyDeferredSnapshot(field); deviceWeight += 2L * source.sizeInBytes();
            var deviceProbe = new CompoundTag() {
                @Override public String toString() { check(Thread.currentThread().getName().equals("avilixlogger-event-worker"), "device SNBT ran on producer"); encodes.incrementAndGet(); return super.toString(); }
            }; deviceProbe.merge(source); pendingDevice.deferSnapshot(field, deviceProbe);
        }
        check(PayloadSizeEstimator.estimate(pendingDevice) >= deviceWeight, "device item/evidence absent from byte accounting");
        storage.append(pendingDevice); pendingDevice.extra = "producer mutation"; storage.shutdown();
        check(rows.size() == 2 && encodes.get() == 3 && Ae2TransferDetails.context(rows.getFirst()).getCompound("Key").equals(tag.getCompound("Key")), "worker lost captured ME payload");
        check(Ae2TransferDetails.context(rows.get(1)).getString("Domain").equals("device-slot") && rows.get(1).itemStackNbt.length() > 3000, "worker lost device metadata/components");
        var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(rows.getFirst()), LogEntry.class);
        check(replay.beAfter.equals(rows.getFirst().beAfter) && Ae2TransferDetails.summary(replay).amount() == Long.MAX_VALUE, "Gzip replay changed ME long/key");
        var directory = java.nio.file.Files.createTempDirectory("ae2-transfer-journal-");
        try {
            var journal = new DiskLogJournal(directory); journal.initialize(); LogIdGenerator.ensure(replay);
            var file = journal.write(journal.newToken(), List.of(replay)); var fromDisk = DiskLogJournal.read(file).getFirst();
            check(fromDisk.id == replay.id && fromDisk.beAfter.equals(replay.beAfter) && Ae2TransferDetails.summary(fromDisk).amount() == Long.MAX_VALUE, "disk journal changed ME key/quantity/ID");
            LogIdGenerator.ensure(device); var deviceDisk = DiskLogJournal.read(journal.write(journal.newToken(), List.of(device))).getFirst();
            check(deviceDisk.id == device.id && deviceDisk.itemStackNbt.equals(device.itemStackNbt) && Ae2TransferDetails.context(deviceDisk).getString("Domain").equals("device-slot"), "journal lost physical slot components/domain");
            var remote = remote(); remote.materializeSnapshots(); LogIdGenerator.ensure(remote);
            var remoteReplay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(remote), LogEntry.class);
            var remoteDisk = DiskLogJournal.read(journal.write(journal.newToken(), List.of(remoteReplay))).getFirst();
            check(remoteDisk.id == remote.id && remoteDisk.source.equals(remote.source) && remoteDisk.actorUuid.equals(remote.actorUuid)
                    && remoteDisk.actorName.equals(remote.actorName) && remoteDisk.itemStackNbt.equals(remote.itemStackNbt) && remoteDisk.beAfter.equals(remote.beAfter)
                    && remoteDisk.x == remote.x && remoteDisk.y == remote.y && remoteDisk.z == remote.z, "journal lost remote provider/terminal/slot/actor/components");
        } finally { try (var files = java.nio.file.Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(path); } }
        System.out.println("AE2_TRANSFER_EVIDENCE_OK: exact long, item/fluid/generic units, legacy/malformed refusal, bounded weight, worker-only SNBT and full key replay");
        System.out.println("AE2_DEVICE_EVIDENCE_OK: exact physical slot delta/components/domain and journal ID replay; no network transfer claimed");
        System.out.println("AE2_REMOTE_EVIDENCE_OK: original terminal, provider coordinates, remote slot/ID, actor and full pattern survive Gzip and disk journal");
    }
}
