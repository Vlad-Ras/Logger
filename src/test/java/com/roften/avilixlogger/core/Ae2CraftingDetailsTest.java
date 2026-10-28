package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

final class Ae2CraftingDetailsTest {
    static LogEntry record(String kind, String outcome) {
        var row = row("AE2 crafting receipt");
        row.type = outcome.equals("finished") ? ActionType.ME_CRAFT_FINISH : outcome.equals("cancelled") ? ActionType.ME_CRAFT_CANCEL : ActionType.ME_CRAFT_REQUEST;
        row.source = "ae2:crafting:" + (outcome.equals("finished") ? "finish" : outcome.equals("cancelled") ? "cancel" : "submit");
        row.actorUuid = UUID.randomUUID(); row.actorName = "NativeCraftRequester";
        row.dim = "minecraft:overworld"; row.x = -81; row.y = 109; row.z = 61;
        var data = new CompoundTag(); data.putString("Origin", "PatternTerminalPart"); data.putString("OriginKind", "part");
        if (outcome.equals("rejected")) data.putString("ErrorCode", "NO_SUITABLE_CPU_FOUND");
        else data.putUUID("JobId", UUID.randomUUID());
        var cpu = new CompoundTag(); cpu.putString("Dimension", "minecraft:the_nether"); cpu.putString("BoundsMin", "31, 70, -9");
        cpu.putString("BoundsMax", "32, 71, -8"); cpu.putString("Name", "Native CPU"); data.put("Cpu", cpu);
        if (outcome.equals("finished")) { row.actorUuid = null; row.actorName = null; row.dim = "minecraft:the_nether"; row.x = 31; row.y = 70; row.z = -9; data.putString("StateTransition", "cpu-success-cleared-job"); }
        String resource = kind.equals("i") ? "ae2:processing_pattern" : kind.equals("f") ? "minecraft:water" : "example:steam";
        String type = kind.equals("i") ? "ae2:item" : kind.equals("f") ? "ae2:fluid" : "example:gas";
        var full = new CompoundTag(); full.putString("id", resource); full.putInt("count", 1);
        var components = new CompoundTag(); components.putString("test:crafting", "exact native components " + "v".repeat(3000)); full.put("components", components);
        if (kind.equals("i")) { row.deferSnapshot(LogEntry.SnapshotField.ITEM, full); row.count = Integer.MAX_VALUE; }
        else data.put("Key", full);
        Ae2CraftingDetails.attach(row, kind, resource, type, Long.MAX_VALUE, outcome, data); return row;
    }
    static void run() throws Exception {
        check(ActionType.ME_CRAFT_REQUEST.ordinal() == ActionType.ITEM_TRADE.ordinal() + 1
                && ActionType.ME_CRAFT_CANCEL.ordinal() == ActionType.ME_CRAFT_REQUEST.ordinal() + 1
                && ActionType.ME_CRAFT_FINISH.ordinal() == ActionType.ME_CRAFT_CANCEL.ordinal() + 1, "job action types not appended");
        var input = List.of(record("i", "accepted"), record("f", "rejected"), record("g", "cancelled"), record("i", "finished"));
        var rows = new CopyOnWriteArrayList<LogEntry>(); var encoded = new AtomicInteger();
        var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { rows.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(rows); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        }, 2, 1024 * 1024);
        try {
            for (var row : input) {
                var data = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
                var probe = new CompoundTag() {
                    @Override public String toString() { check(Thread.currentThread().getName().equals("avilixlogger-event-worker"), "crafting evidence SNBT encoded on producer"); encoded.incrementAndGet(); return super.toString(); }
                }; probe.merge(data); row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, probe);
                check(PayloadSizeEstimator.estimate(row) >= 2L * data.sizeInBytes(), "job receipt absent from queue byte weight"); storage.append(row);
            }
        } finally { storage.shutdown(); }
        check(rows.size() == 4 && encoded.get() == 4, "job queue dropped/duplicated rows or encoded twice");
        var dir = java.nio.file.Files.createTempDirectory("ae2-crafting-journal-");
        try {
            var journal = new DiskLogJournal(dir); journal.initialize();
            for (var row : rows) {
                check(Ae2CraftingDetails.summary(row).amount() == Long.MAX_VALUE && Ae2CraftingDetails.context(row) != null, "job long/evidence lost");
                check(Ae2CraftingDetails.summary(row).label().contains(Long.toString(Long.MAX_VALUE)) && !Ae2CraftingDetails.summary(row).label().contains("ae2-crafting-v1"), "job feed label truncated amount/leaked prefix");
                check(Ae2CraftingDetails.describe(row).stream().anyMatch(c -> c.getString().contains("планируемому")), "planned output claimed physical production");
                LogIdGenerator.ensure(row); var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
                var disk = DiskLogJournal.read(journal.write(journal.newToken(), List.of(replay))).getFirst();
                check(disk.id == row.id && disk.extra.equals(row.extra) && disk.beAfter.equals(row.beAfter) && Objects.equals(disk.itemStackNbt, row.itemStackNbt)
                        && Objects.equals(disk.actorUuid, row.actorUuid) && Objects.equals(disk.actorName, row.actorName) && disk.x == row.x && disk.type == row.type, "journal lost job receipt/actor/address/ID");
            }
        } finally { try (var paths = java.nio.file.Files.walk(dir)) { for (var p : paths.sorted(Comparator.reverseOrder()).toList()) java.nio.file.Files.delete(p); } }
        for (String value : List.of("ae2-crafting-v1|i|minecraft:stone|ae2:item|-1|accepted", "ae2-crafting-v1|i|minecraft:stone|ae2:item|9223372036854775808|accepted", "ae2-crafting-v1|i|bad id|ae2:item|1|accepted", "ae2-crafting-v1|i|minecraft:stone|ae2:item|1|completed")) {
            var bad = rows.getFirst().copyForQueue(); bad.extra = value; check(Ae2CraftingDetails.summary(bad) == null, "malformed job summary accepted");
        }
        var mismatch = rows.getFirst().copyForQueue(); mismatch.beAfter = mismatch.beAfter.replace("planned-output", "produced-items");
        check(Ae2CraftingDetails.context(mismatch) == null, "unknown job quantity meaning accepted");
        mismatch = rows.getFirst().copyForQueue(); mismatch.type = ActionType.ME_CRAFT_CANCEL;
        check(Ae2CraftingDetails.summary(mismatch) == null, "accepted submission read as confirmed cancellation");
        int mask = CaptureTypePolicy.mask();
        try { CaptureTypePolicy.install(CaptureTypePolicy.changed(mask, 2, false));
            check(!CaptureTypePolicy.allows(ActionType.ME_CRAFT_REQUEST, "ae2:crafting", null)
                    && !CaptureTypePolicy.allows(ActionType.ME_CRAFT_CANCEL, "ae2:crafting", null)
                    && !CaptureTypePolicy.allows(ActionType.ME_CRAFT_FINISH, "ae2:crafting", null), "job types bypassed category gate");
        } finally { CaptureTypePolicy.install(mask); }
        System.out.println("AE2_CRAFTING_EVIDENCE_OK: append-only types, planned long output, typed outcome, full key, worker SNBT, queue weight, journal/Gzip, actor/address and malformed refusal");
    }
}
