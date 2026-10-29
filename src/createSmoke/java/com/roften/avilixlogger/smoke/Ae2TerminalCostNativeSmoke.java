package com.roften.avilixlogger.smoke;

import appeng.api.storage.ITerminalHost;
import appeng.api.implementations.blockentities.IViewCellStorage;
import appeng.core.definitions.AEItems;
import appeng.menu.me.common.MEStorageMenu;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Real powered storage terminal. Data-kernel allocation comparison, not production AuthCore/MSPT acceptance. */
public final class Ae2TerminalCostNativeSmoke {
    private static volatile Ae2Audit.Snapshot sink;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static class ProbeMenu extends MEStorageMenu {
        int starts, ends;
        ProbeMenu(ServerPlayer player, ITerminalHost host) {
            super(TYPE, 173, player.getInventory(), host);
            registerClientAction("native-cost-noop", () -> {});
        }
        // Dev-only virtual entry probes. AuthCore remains disabled; these never enable/append capture.
        public void avilixlogger$beforeAction() { starts++; }
        public void avilixlogger$afterAction() { ends++; }
    }
    private record Cost(long bytes, long cpuNanos, long wallNanos) {}
    private static Cost measure(ProbeMenu menu, Ae2Audit.Snapshot baseline, boolean optimized, int iterations,
                                com.sun.management.ThreadMXBean bean) {
        long thread = Thread.currentThread().threadId();
        long bytes = bean.getThreadAllocatedBytes(thread), cpu = bean.getCurrentThreadCpuTime(), wall = System.nanoTime();
        for (int i = 0; i < iterations; i++) sink = optimized ? Ae2Audit.followUpSnapshot(menu, baseline) : Ae2Audit.snapshot(menu);
        return new Cost(bean.getThreadAllocatedBytes(thread) - bytes, bean.getCurrentThreadCpuTime() - cpu, System.nanoTime() - wall);
    }
    public static void run(ServerPlayer player, ITerminalHost host) throws Exception {
        check(!Ae2Audit.enabled(), "terminal cost fixture enabled production authorization");
        var previousMenu = player.containerMenu; var menu = new ProbeMenu(player, host); player.containerMenu = menu;
        var cells = ((IViewCellStorage)host).getViewCellStorage();
        var saved = new ArrayList<ItemStack>(); for (int i = 0; i < cells.size(); i++) saved.add(cells.getStackInSlot(i).copy());
        try {
            check(menu instanceof Ae2MenuCapture, "terminal cost mixin absent");
            menu.receiveClientAction("native-cost-noop", "null");
            check(menu.starts == 1 && menu.ends == 1, "dev entry probes missed a real native action");
            menu.starts = 0; menu.ends = 0;
            for (int i = 0; i < 1000; i++) menu.broadcastChanges();
            check(menu.starts == 0 && menu.ends == 0, "idle terminal broadcast entered Logger action capture");

            for (int i = 0; i < cells.size(); i++) {
                var cell = AEItems.VIEW_CELL.stack(); var data = new CompoundTag();
                data.putString("snapshot-cost", "v".repeat(4096) + i); cell.set(DataComponents.CUSTOM_DATA, CustomData.of(data));
                cells.setItemDirect(i, cell);
            }
            var baseline = Objects.requireNonNull(Ae2Audit.snapshot(menu));
            check(baseline.slots().size() >= cells.size() && cells.size() == 5, "terminal physical view-cell layout changed");
            var follow = Objects.requireNonNull(Ae2Audit.followUpSnapshot(menu, baseline));
            int reused = 0; for (var entry : baseline.slots().entrySet()) if (follow.slots().get(entry.getKey()) == entry.getValue()) reused++;
            check(reused == baseline.slots().size() && Ae2Audit.changes(menu, baseline, follow).isEmpty(), "unchanged terminal copied stacks or fabricated rows");

            var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            check(bean.isThreadAllocatedMemorySupported() && bean.isCurrentThreadCpuTimeSupported(), "JDK cannot measure native data-kernel cost");
            bean.setThreadAllocatedMemoryEnabled(true); bean.setThreadCpuTimeEnabled(true);
            for (int i = 0; i < 4000; i++) { sink = Ae2Audit.snapshot(menu); sink = Ae2Audit.followUpSnapshot(menu, baseline); }
            int iterations = 20000;
            var a1 = measure(menu, baseline, false, iterations, bean);
            var b = measure(menu, baseline, true, iterations, bean);
            var a2 = measure(menu, baseline, false, iterations, bean);
            check(b.bytes() < a1.bytes() && b.bytes() < a2.bytes(), "follow-up allocation did not improve both full-copy controls");

            var changed = AEItems.VIEW_CELL.stack(); var data = new CompoundTag(); data.putString("new", "changed-components");
            changed.set(DataComponents.CUSTOM_DATA, CustomData.of(data)); cells.setItemDirect(0, changed);
            var optimized = Objects.requireNonNull(Ae2Audit.followUpSnapshot(menu, baseline));
            var full = Objects.requireNonNull(Ae2Audit.snapshot(menu));
            var actual = Ae2Audit.changes(menu, baseline, optimized); var reference = Ae2Audit.changes(menu, baseline, full);
            check(actual.size() == 1 && reference.size() == 1, "component-only terminal change lost or duplicated");
            var row = actual.getFirst(); var expected = reference.getFirst();
            check(row.type == expected.type && row.source.equals(expected.source) && Objects.equals(row.actorUuid, expected.actorUuid)
                    && row.x == expected.x && row.y == expected.y && row.z == expected.z
                    && Objects.equals(row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_BEFORE), expected.copyDeferredSnapshot(LogEntry.SnapshotField.BE_BEFORE))
                    && Objects.equals(row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER), expected.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)), "optimized terminal evidence differs from full-copy reference");
            int copied = 0; for (var entry : baseline.slots().entrySet()) if (optimized.slots().get(entry.getKey()) != entry.getValue()) copied++;
            check(copied == 1, "single terminal change copied unchanged physical slots");
            cells.setItemDirect(0, ItemStack.EMPTY);
            check(optimized.slots().values().stream().anyMatch(v -> v.stack().has(DataComponents.CUSTOM_DATA)
                    && v.stack().get(DataComponents.CUSTOM_DATA).copyTag().contains("new")), "optimized after snapshot retained live mutable slot");
            var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = new Thread(() -> { try { check(Ae2Audit.followUpSnapshot(menu, baseline) == null, "follow-up accepted off-thread reads"); } catch (Throwable t) { error.set(t); } });
            worker.start(); worker.join(5000); check(!worker.isAlive() && error.get() == null, "off-thread follow-up failed");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info(
                    "AE2_TERMINAL_COST_NATIVE_OK: real powered MEStorageMenu; idleBroadcasts=1000 auditEntries=0, live action probe=1; unchangedReused={}, changedCopies=1; A/B/A data-kernel iterations={} bytesPerOp={}/{}/{} cpuNsPerOp={}/{}/{} wallNsPerOp={}/{}/{}; no-op and component-only receipt parity, native detachment/off-thread guard; dev entry override and data-only kernels, production AuthCore/MSPT not tested",
                    reused, iterations, a1.bytes()/iterations, b.bytes()/iterations, a2.bytes()/iterations,
                    a1.cpuNanos()/iterations, b.cpuNanos()/iterations, a2.cpuNanos()/iterations,
                    a1.wallNanos()/iterations, b.wallNanos()/iterations, a2.wallNanos()/iterations);
        } finally {
            sink = null; for (int i = 0; i < saved.size(); i++) cells.setItemDirect(i, saved.get(i));
            menu.removed(player); player.containerMenu = previousMenu;
        }
    }
}
