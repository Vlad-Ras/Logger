package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

final class Ae2ConfigurationDetailsTest {
    private static CompoundTag item(String id, String value) {
        var tag = new CompoundTag(); tag.putString("id", id); tag.putInt("count", 1);
        var components = new CompoundTag(); var data = new CompoundTag(); data.putString("value", value + "x".repeat(3000));
        components.put("minecraft:custom_data", data); tag.put("components", components); return tag;
    }
    static LogEntry record(ActionType type) {
        var row = AsyncPipelineTest.row("ME configuration fixture"); row.type = type; row.source = "ae2:NativeDeviceMenu";
        row.dim = "minecraft:overworld"; row.x = -41; row.y = 101; row.z = 32; row.actorUuid = UUID.randomUUID(); row.actorName = "NativeConfigActor";
        if (type == ActionType.ME_SETTING_CHANGE) Ae2ConfigurationDetails.setting(row, "avilixlogger:reporting_value", "4294967297", Long.toString(Long.MAX_VALUE));
        else {
            String semantic = type == ActionType.ME_FILTER_CHANGE ? "CONFIG" : type == ActionType.ME_UPGRADE_CHANGE ? "UPGRADE" : "STORAGE_CELL";
            String oldId = type == ActionType.ME_UPGRADE_CHANGE ? "ae2:speed_card" : type == ActionType.ME_CELL_CHANGE ? "ae2:item_storage_cell_1k" : "ae2:wrapped_generic_stack";
            String newId = type == ActionType.ME_UPGRADE_CHANGE ? "ae2:capacity_card" : oldId;
            CompoundTag before = item(oldId, "old-components"), after = item(newId, "new-components");
            var oldResource = type == ActionType.ME_FILTER_CHANGE ? new Ae2ConfigurationDetails.Resource("f", "minecraft:water", (1L << 40) + 1) : new Ae2ConfigurationDetails.Resource("i", oldId, 1);
            var newResource = type == ActionType.ME_FILTER_CHANGE ? new Ae2ConfigurationDetails.Resource("f", "minecraft:lava", Long.MAX_VALUE) : new Ae2ConfigurationDetails.Resource("i", newId, 1);
            Ae2ConfigurationDetails.slot(row, semantic, 7, oldResource, newResource, before, after);
        }
        return row;
    }
    static void run() throws Exception {
        for (var type : List.of(ActionType.ME_FILTER_CHANGE, ActionType.ME_UPGRADE_CHANGE, ActionType.ME_CELL_CHANGE, ActionType.ME_SETTING_CHANGE)) {
            var row = record(type); check(row.beBefore == null && row.beAfter == null, "configuration encoded before worker"); row.materializeSnapshots();
            var context = Ae2ConfigurationDetails.context(row); check(context != null, "typed before/after lost");
            if (type == ActionType.ME_FILTER_CHANGE || type == ActionType.ME_SETTING_CHANGE) check(Ae2ConfigurationDetails.label(row).contains(Long.toString(Long.MAX_VALUE)), "exact long lost");
            if (type != ActionType.ME_SETTING_CHANGE) check(!context.before().getCompound("Item").equals(context.after().getCompound("Item")), "complete component variants collapsed");
            var invalid = row.copyForQueue(); invalid.beAfter = row.beAfter.replace("Version:1", "Version:9"); check(Ae2ConfigurationDetails.context(invalid) == null, "unknown configuration schema accepted");
            invalid = row.copyForQueue(); invalid.beBefore = null; check(Ae2ConfigurationDetails.context(invalid) == null, "partial configuration became a full pair");
            invalid = row.copyForQueue(); invalid.type = ActionType.ME_PUT; check(Ae2ConfigurationDetails.context(invalid) == null, "transfer reinterpreted as configuration");
        }
        var presence = record(ActionType.ME_SETTING_CHANGE); Ae2ConfigurationDetails.setting(presence, "presence|unicode", null, ""); presence.materializeSnapshots();
        var context = Ae2ConfigurationDetails.context(presence); check(!context.before().getBoolean("Present") && context.after().getBoolean("Present") && context.after().getString("Value").isEmpty(), "absent and empty setting conflated");
        var legacy = AsyncPipelineTest.row("slot 7: old → new"); legacy.type = ActionType.ME_FILTER_CHANGE;
        check(Ae2ConfigurationDetails.label(legacy) == null && Ae2ConfigurationDetails.describe(legacy).isEmpty(), "legacy configuration reinterpreted");
        var rows = new CopyOnWriteArrayList<LogEntry>(); var sink = new LogStorage() {
            public void append(LogEntry row) { rows.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(rows); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        };
        var row = record(ActionType.ME_CELL_CHANGE); var encodes = new AtomicInteger(); long weight = 0;
        for (var field : List.of(LogEntry.SnapshotField.BE_BEFORE, LogEntry.SnapshotField.BE_AFTER)) {
            var source = (CompoundTag) row.copyDeferredSnapshot(field); weight += 2L * source.sizeInBytes();
            var probe = new CompoundTag() {
                @Override public String toString() { check(Thread.currentThread().getName().equals("avilixlogger-event-worker"), "ME configuration SNBT ran on producer"); encodes.incrementAndGet(); return super.toString(); }
            }; probe.merge(source); row.deferSnapshot(field, probe);
        }
        check(PayloadSizeEstimator.estimate(row) >= weight, "configuration before/after absent from byte accounting");
        var storage = new AsyncLogStorage(sink, 4, 1024 * 1024); storage.append(row); row.actorName = "changed-after-handoff"; row.extra = "changed"; storage.shutdown();
        check(rows.size() == 1 && encodes.get() == 2 && Ae2ConfigurationDetails.context(rows.getFirst()) != null && rows.getFirst().actorName.equals("NativeConfigActor"), "worker lost configuration pair/metadata");
        var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(rows.getFirst()), LogEntry.class);
        var directory = Files.createTempDirectory("ae2-configuration-");
        try {
            LogIdGenerator.ensure(replay); var journal = new DiskLogJournal(directory); journal.initialize();
            var read = DiskLogJournal.read(journal.write(journal.newToken(), List.of(replay))).getFirst();
            check(read.id == replay.id && read.beBefore.equals(replay.beBefore) && read.beAfter.equals(replay.beAfter) && read.extra.equals(replay.extra), "journal changed complete configuration pair");
        } finally { try (var files = Files.walk(directory)) { for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path); } }
        System.out.println("AE2_CONFIGURATION_EVIDENCE_OK: filter/upgrade/cell/setting full pairs, exact long/components/presence, malformed/legacy refusal, worker SNBT/weight and journal replay");
    }
}
