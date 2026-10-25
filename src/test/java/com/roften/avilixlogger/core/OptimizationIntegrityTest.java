package com.roften.avilixlogger.core;

import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** Regressions for information lost by drop aggregation, dedup and SQL batching. */
final class OptimizationIntegrityTest {
    static void run() throws Exception {
        dropLocationsAndTime();
        exactDuplicateIdentity();
        separateContainerActions();
        detachedMenuWorker();
    }
    private static LogEntry delta(String item, int count) {
        LogEntry row = row("container delta"); row.type = ActionType.CONTAINER_PUT;
        row.dim = "minecraft:overworld"; row.itemStackNbt = item; row.count = count;
        row.actorUuid = new UUID(1, 2); row.actorName = "Maxim"; return row;
    }
    private static void dropLocationsAndTime() {
        List<LogEntry> rows = new ArrayList<>(); DropAggregator drops = new DropAggregator(rows::add);
        LogEntry a = delta("stone", 2); a.type = ActionType.ITEM_DROP; a.ts = 1000; a.x = 10;
        LogEntry b = a.copyForQueue(); b.count = 3; b.x = 10000; b.ts = 1010;
        drops.add(a, 0); drops.add(b, 10); drops.flush(11, true);
        check(rows.size() == 2 && rows.get(0).x == 10 && rows.get(0).count == 2
                && rows.get(1).x == 10000 && rows.get(1).count == 3,
                "teleport merged separate drop locations and moved the count to the last position");
        rows.clear(); drops = new DropAggregator(rows::add);
        for (int i = 0; i < 10; i++) { LogEntry r = delta("stone", 1); r.type = ActionType.ITEM_DROP;
            r.ts = 2000 + i * 100; drops.add(r, i * 100); }
        drops.flush(1000, true);
        check(rows.size() > 1 && rows.stream().mapToInt(r -> r.count).sum() == 10,
                "continuous drops collapsed unbounded history into one timestamp");
    }
    private static void exactDuplicateIdentity() throws Exception {
        Class<?> type = Class.forName("com.roften.avilixlogger.core.DeduplicatingLogStorage$Fingerprint");
        var of = type.getDeclaredMethod("of", LogEntry.class); of.setAccessible(true);
        LogEntry a = delta("stone", 1), b = a.copyForQueue(); b.count = 64;
        check(!of.invoke(null, a).equals(of.invoke(null, b)), "dedup confused different item counts");
        a.type = b.type = ActionType.BLOCK_INTERACT; a.blockAfter = "water[level=1]"; b.blockAfter = "water[level=2]";
        check(!of.invoke(null, a).equals(of.invoke(null, b)), "dedup ignored the actual after state");
        a = delta("A".repeat(1024), 1); b = a.copyForQueue();
        b.itemStackNbt = a.itemStackNbt.substring(0, 15) + "B" + a.itemStackNbt.substring(16);
        check(!of.invoke(null, a).equals(of.invoke(null, b)), "sampled NBT fingerprint hid a change between sampled characters");
        b = a.copyForQueue(); b.source = "system:container_interval";
        check(!of.invoke(null, a).equals(of.invoke(null, b)), "dedup erased different evidence sources");
        var window = DeduplicatingLogStorage.class.getDeclaredMethod("duplicateWindowMs", ActionType.class); window.setAccessible(true);
        check((long) window.invoke(null, ActionType.CONTAINER_PUT) == 0L
                && (long) window.invoke(null, ActionType.CONTAINER_TAKE) == 0L,
                "independent repeated container clicks must not be deduplicated by elapsed time");
    }
    @SuppressWarnings("unchecked")
    private static void separateContainerActions() throws Exception {
        var merge = ClickHouseLogStorage.class.getDeclaredMethod("coalesceBatch", List.class); merge.setAccessible(true);
        LogEntry a = delta("stone", 1), b = a.copyForQueue(); a.id = 100; b.id = 101;
        a.ts = 1000; b.ts = 1100; a.source = "player:container_click"; b.source = "system:container_interval";
        List<LogEntry> out = (List<LogEntry>) merge.invoke(null, List.of(a, b));
        check(out.size() == 2 && out.get(0).id == 100 && out.get(1).id == 101
                && out.get(0).count == 1 && out.get(1).count == 1 && out.get(1).ts == 1100,
                "SQL batching merged distinct container actions, timestamps or sources");
    }
    private static void detachedMenuWorker() {
        int found=0;
        for(var method:LoggerEventHandlers.MenuAction.class.getDeclaredMethods())if(method.getName().startsWith("lambda$emit$")) {
            check(java.lang.reflect.Modifier.isStatic(method.getModifiers()),"menu worker retains live player/menu scope");found++;
            for(var type:method.getParameterTypes())check(!net.minecraft.world.entity.Entity.class.isAssignableFrom(type)
                    && !net.minecraft.world.level.Level.class.isAssignableFrom(type)
                    && !net.minecraft.world.inventory.AbstractContainerMenu.class.isAssignableFrom(type),"menu worker receives live game state");
        }
        check(found==1,"detached menu encoder closure was not verified");
        System.out.println("MENU_WORKER_ISOLATION_OK: no live menu, player or level captured by background delta encoding");
    }
    public static void main(String[] args) throws Exception { run(); System.out.println("Optimization integrity checks passed."); }
}
