package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

/** Behaviour checks for the additional 2.1.10 tick optimizations. */
final class TickHotPathTest {
    static void run() {
        snapshotHandoff();
        blockActionKinds();
        spatialWindow();
    }

    private static void snapshotHandoff() {
        LogEntry row = new LogEntry();
        long bytes = 0;
        for (LogEntry.SnapshotField field : LogEntry.SnapshotField.values()) {
            CompoundTag tag = new CompoundTag(); tag.putString("field", field.name());
            row.deferSnapshot(field, tag);
            row.deferSnapshot(field, tag);
            bytes += tag.sizeInBytes();
        }
        check(row.snapshotBytes() == bytes, "repeated snapshot registration changed memory accounting");
        LogEntry queued = row.copyForQueue();
        CompoundTag replacement = new CompoundTag(); replacement.putString("field", "replacement");
        row.deferSnapshot(LogEntry.SnapshotField.ITEM, replacement);
        queued.materializeSnapshots(); row.materializeSnapshots();
        String[] encoded = {queued.beBefore, queued.beAfter, queued.itemStackNbt,
                queued.entityNbt, queued.containerSlotsBefore, queued.containerSlotsAfter};
        var fields = LogEntry.SnapshotField.values();
        for (int i = 0; i < fields.length; i++)
            check(NbtSerde.fromSnbt(encoded[i]).getString("field").equals(fields[i].name()),
                    "queued snapshot changed or encoded into the wrong field: " + fields[i]);
        check(NbtSerde.fromSnbt(row.itemStackNbt).getString("field").equals("replacement"), "snapshot replacement lost");
        check(row.snapshotBytes() == 0 && queued.snapshotBytes() == 0, "encoded snapshot retained its reservation");
    }

    private static void blockActionKinds() {
        check(SetBlockCapture.classify(true, false, true, CauseContext.Kind.USE_BLOCK) == ActionType.BLOCK_PLACE,
                "placing a block during an interaction must remain BLOCK_PLACE");
        check(SetBlockCapture.classify(false, true, true, CauseContext.Kind.USE_ITEM) == ActionType.BLOCK_BREAK,
                "removing a block during item use must remain BLOCK_BREAK");
        check(SetBlockCapture.classify(false, false, true, CauseContext.Kind.USE_BLOCK) == ActionType.BLOCK_INTERACT,
                "a player state change lost its interaction type");
        check(SetBlockCapture.classify(false, false, true, null) == ActionType.BLOCK_PLACE,
                "a system block replacement changed type");
        check(SetBlockCapture.classify(false, false, false, null) == ActionType.BLOCK_ENTITY_NBT_CHANGE,
                "a data-only change lost its type");
    }

    private static void spatialWindow() {
        SpatialActionWindow<BlockPos> window = new SpatialActionWindow<>(64, p -> p);
        ArrayDeque<BlockPos> reference = new ArrayDeque<>();
        check(!window.mayContain(BlockPos.ZERO, 16), "empty history should be skipped");
        Random random = new Random(74121);
        for (int n = 0; n < 4000; n++) {
            // Includes boundary coordinates and distant teleports followed by history eviction.
            int base = (n / 64 % 2 == 0) ? -30_000_000 : 30_000_000;
            BlockPos point = new BlockPos(base + random.nextInt(33), random.nextInt(512) - 128, random.nextInt(256) - 128);
            window.add(point); reference.addFirst(point);
            if (reference.size() > 64) reference.removeLast();
            check(window.size() == reference.size(), "spatial history exceeded its capacity");
            for (BlockPos retained : reference)
                check(window.mayContain(retained, 0), "spatial filter excluded a retained action");
            BlockPos target = point.offset(random.nextInt(17) - 8, random.nextInt(17) - 8, random.nextInt(17) - 8);
            double radiusSquared = (n % 3 == 0) ? 0 : (n % 3 == 1) ? 16 : 576;
            List<BlockPos> expected = reference.stream().filter(p -> p.distSqr(target) <= radiusSquared).toList();
            List<BlockPos> actual = new ArrayList<>();
            if (window.mayContain(target, radiusSquared))
                for (BlockPos candidate : window) if (candidate.distSqr(target) <= radiusSquared) actual.add(candidate);
            check(actual.equals(expected), "spatial filter changed candidates or newest-first order");
        }

        int visited = 0;
        for (int player = 0; player < 128; player++) {
            SpatialActionWindow<BlockPos> history = new SpatialActionWindow<>(64, p -> p);
            for (int action = 0; action < 64; action++) history.add(new BlockPos(player * 4096 + action % 3, 64, 0));
            if (history.mayContain(new BlockPos(0, 64, 0), 16)) for (BlockPos ignored : history) visited++;
        }
        check(visited == 64, "distant histories were not rejected before iteration");
        System.out.println("Spatial rejection fixture: visited " + visited + " of 8192 actions; candidate equivalence passed");
    }
}
