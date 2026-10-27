package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.*;
import appeng.api.stacks.*;
import appeng.api.storage.*;
import appeng.core.definitions.AEBlocks;
import appeng.me.storage.NetworkStorage;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/** Actual AE2 routing/power + detached evidence kernel; production authorization remains disabled. */
public final class Ae2TransferNativeSmoke {
    private static final RuntimeException FAULT = new RuntimeException("intentional ME storage failure");
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static final class Memory implements MEStorage {
        final Map<AEKey, Long> values = new HashMap<>();
        final long capacity;
        int mutations;
        boolean fail;
        Memory(long capacity) { this.capacity = capacity; }
        long stored(AEKey key) { return values.getOrDefault(key, 0L); }
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (fail) throw FAULT;
            long actual = Math.min(amount, capacity - stored(key));
            if (mode == Actionable.MODULATE && actual > 0) { mutations++; values.put(key, stored(key) + actual); }
            return actual;
        }
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (fail) throw FAULT;
            long actual = Math.min(amount, stored(key));
            if (mode == Actionable.MODULATE && actual > 0) { mutations++; values.put(key, stored(key) - actual); }
            return actual;
        }
        public void getAvailableStacks(KeyCounter counter) { values.forEach(counter::add); }
        public Component getDescription() { return Component.literal("bounded native test storage"); }
    }
    private static final class Power implements IEnergySource {
        double available;
        Power(double value) { available = value; }
        public double extractAEPower(double amount, Actionable mode, appeng.api.config.PowerMultiplier multiplier) {
            double actual = Math.min(available, amount);
            if (mode == Actionable.MODULATE) available -= actual;
            return actual;
        }
    }

    public static void run(ServerLevel level, ServerPlayer player) throws Exception {
        check(!Ae2Audit.enabled(), "transfer fixture bypassed AuthCore");
        for (var type : List.of(NetworkStorage.class, StorageHelper.class))
            for (String hook : List.of("avilixlogger$insert", "avilixlogger$extract"))
                check(Arrays.stream(type.getDeclaredMethods()).anyMatch(method -> method.getName().contains(hook)), "native storage hook missing: " + type + " / " + hook);
        var item = new ItemStack(Items.DIAMOND);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("native ME item components"));
        var key = AEItemKey.of(item);
        var playerSource = IActionSource.ofPlayer(player);
        var memory = new Memory(37); var network = new NetworkStorage(); network.mount(100, memory);
        var rows = new ArrayList<LogEntry>();
        long simulated = network.insert(key, 100, Actionable.SIMULATE, playerSource);
        check(simulated == 37 && memory.stored(key) == 0 && Ae2Audit.transferRow(key, simulated, Actionable.SIMULATE, playerSource, true) == null, "simulation mutated storage or produced evidence");
        long actual = network.insert(key, 100, Actionable.MODULATE, playerSource);
        check(actual == 37 && memory.stored(key) == 37 && memory.mutations == 1, "partial native insert changed amount or repeated mutation");
        var inserted = required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, playerSource, true)); rows.add(inserted);
        check(player.getUUID().equals(inserted.actorUuid) && player.getName().getString().equals(inserted.actorName), "real player attribution lost");
        var machinePos = new BlockPos(62, 100, 60);
        level.setBlockAndUpdate(machinePos, AEBlocks.INTERFACE.block().defaultBlockState());
        try {
            var host = Objects.requireNonNull(level.getBlockEntity(machinePos));
            int[] nodeReads = {0}, ownerReads = {0};
            var node = (IGridNode) Proxy.newProxyInstance(IGridNode.class.getClassLoader(), new Class[]{IGridNode.class}, (p, method, args) -> {
                if (method.getName().equals("getOwner")) { ownerReads[0]++; return host; }
                throw new AssertionError("Logger queried unrelated grid API " + method);
            });
            IActionHost machine = () -> { nodeReads[0]++; return node; };
            var machineSource = IActionSource.ofMachine(machine);
            actual = network.extract(key, 20, Actionable.MODULATE, machineSource);
            check(actual == 20 && memory.stored(key) == 17, "partial extraction altered native balance");
            var extracted = required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, machineSource, false)); rows.add(extracted);
            check(nodeReads[0] == 1 && ownerReads[0] == 1 && extracted.actorUuid == null && extracted.x == machinePos.getX() && extracted.y == machinePos.getY() && extracted.z == machinePos.getZ(), "machine source repeated node/owner lookup or lost its actual position");
            var second = new Memory(13); network.mount(0, second);
            actual = network.insert(key, 99, Actionable.MODULATE, machineSource);
            check(actual == 33 && memory.stored(key) == 37 && second.stored(key) == 13, "priority routing/fallback balance changed");
            rows.add(required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, machineSource, true)));
            var combined = IActionSource.ofPlayer(player, machine);
            actual = network.extract(key, 2, Actionable.MODULATE, combined);
            var playerAtMachine = required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, combined, false));
            check(actual == 2 && player.getUUID().equals(playerAtMachine.actorUuid) && playerAtMachine.x == machinePos.getX() && playerAtMachine.z == machinePos.getZ(), "player+machine source lost player or actual device address");
            rows.add(playerAtMachine);
            var bigMemory = new Memory(Long.MAX_VALUE); var bigNetwork = new NetworkStorage(); bigNetwork.mount(0, bigMemory);
            long big = (1L << 32) + 123;
            actual = bigNetwork.insert(key, big, Actionable.MODULATE, playerSource);
            check(actual == big && bigMemory.stored(key) == big, "native long transfer truncated");
            var bigRow = required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, playerSource, true)); rows.add(bigRow);
            check(Ae2TransferDetails.summary(bigRow).amount() == big && bigRow.count == Integer.MAX_VALUE, "full long quantity absent beside legacy int count");
            var fluidStack = new FluidStack(Fluids.WATER, 1000);
            fluidStack.set(DataComponents.CUSTOM_NAME, Component.literal("native ME fluid components"));
            var fluidKey = AEFluidKey.of(fluidStack);
            var fluidMemory = new Memory(1900); var fluidNetwork = new NetworkStorage(); fluidNetwork.mount(0, fluidMemory);
            actual = fluidNetwork.insert(fluidKey, 1843, Actionable.MODULATE, machineSource);
            check(actual == 1843, "native fluid insert altered raw amount");
            var fluidRow = required(Ae2Audit.transferRow(fluidKey, actual, Actionable.MODULATE, machineSource, true)); rows.add(fluidRow);
            actual = fluidNetwork.extract(fluidKey, 487, Actionable.MODULATE, machineSource);
            check(actual == 487 && fluidMemory.stored(fluidKey) == 1356, "native fluid extraction balance changed");
            rows.add(required(Ae2Audit.transferRow(fluidKey, actual, Actionable.MODULATE, machineSource, false)));
            var poweredMemory = new Memory(100); var poweredNetwork = new NetworkStorage(); poweredNetwork.mount(0, poweredMemory);
            double operation = Math.max(1, key.getAmountPerOperation());
            var power = new Power(8 / operation);
            simulated = StorageHelper.poweredInsert(power, poweredNetwork, key, 100, machineSource, Actionable.SIMULATE);
            check(simulated == 8 && poweredMemory.stored(key) == 0 && power.available == 8 / operation, "powered simulation consumed resources");
            actual = StorageHelper.poweredInsert(power, poweredNetwork, key, 100, machineSource, Actionable.MODULATE);
            check(actual == 8 && poweredMemory.stored(key) == 8 && poweredMemory.mutations == 1, "energy-limited insert repeated or recorded requested amount");
            rows.add(required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, machineSource, true)));
            power.available = 5 / operation;
            actual = StorageHelper.poweredExtraction(power, poweredNetwork, key, 100, machineSource, Actionable.MODULATE);
            check(actual == 5 && poweredMemory.stored(key) == 3 && poweredMemory.mutations == 2, "energy-limited extraction changed native amount");
            rows.add(required(Ae2Audit.transferRow(key, actual, Actionable.MODULATE, machineSource, false)));
            check(StorageHelper.poweredInsert(IEnergySource.empty(), poweredNetwork, key, 100, machineSource, Actionable.MODULATE) == 0, "empty power transferred resources");
            poweredMemory.fail = true;
            try { StorageHelper.poweredInsert(new Power(1000), poweredNetwork, key, 1, machineSource, Actionable.MODULATE); throw new AssertionError("native storage exception swallowed"); }
            catch (RuntimeException failure) { check(failure == FAULT, "native exception replaced"); }
            check(!Ae2Audit.insidePowered(), "failed native action retained suppression scope");
            gates(key);
            var fake = net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(level);
            var fakeRow = required(Ae2Audit.transferRow(key, 1, Actionable.MODULATE, IActionSource.ofPlayer(fake), true));
            check(fakeRow.actorUuid == null && fakeRow.actorName.equals("SYSTEM"), "FakePlayer became a human actor");
            rows.add(fakeRow);
            pipeline(rows, level, fluidKey);
        } finally { level.removeBlock(machinePos, false); }
        org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_TRANSFER_NATIVE_OK: real network simulation/partial/priority/long/item+fluid/power/failure, one owner lookup, actor/machine/fake identity, detached worker and full native key replay; authenticated emit not tested");
    }

    private static void gates(AEKey key) throws Exception {
        IActionSource poison = new IActionSource() {
            public Optional<net.minecraft.world.entity.player.Player> player() { throw new AssertionError("unexpected action-source read"); }
            public Optional<IActionHost> machine() { throw new AssertionError("unexpected machine read"); }
            public <T> Optional<T> context(Class<T> type) { return Optional.empty(); }
        };
        check(Ae2Audit.transferRow(key, 0, Actionable.MODULATE, poison, true) == null && Ae2Audit.transferRow(key, 1, Actionable.SIMULATE, poison, true) == null, "empty/simulation touched source");
        Ae2Audit.transfer(key, 1, Actionable.MODULATE, poison, true);
        check(Ae2Audit.transferRow(key, 1, Actionable.MODULATE, IActionSource.empty(), true) == null, "anonymous transfer invented position");
        var error = new AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { check(Ae2Audit.transferRow(key, 1, Actionable.MODULATE, poison, true) == null, "worker captured live ME state"); } catch (Throwable t) { error.set(t); } }, "ae2-transfer-wrong-thread");
        thread.start(); thread.join(5000); check(!thread.isAlive() && error.get() == null, "off-thread transfer touched source/key");
    }
    private static LogEntry required(LogEntry row) { check(row != null, "native transfer evidence missing"); return row; }
    private static void pipeline(List<LogEntry> expected, ServerLevel level, AEFluidKey fluidKey) {
        var actual = new CopyOnWriteArrayList<LogEntry>();
        var sink = new LogStorage() {
            public void append(LogEntry entry) { actual.add(entry); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(actual); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        };
        var storage = new AsyncLogStorage(sink, 4, 1024 * 1024);
        try { expected.forEach(storage::append); } finally { storage.shutdown(); }
        check(actual.size() == expected.size(), "ordered native pipeline lost/duplicated ME records");
        for (int i = 0; i < actual.size(); i++) {
            var row = actual.get(i); var summary = Ae2TransferDetails.summary(row);
            check(summary.equals(Ae2TransferDetails.summary(expected.get(i))) && row.x == expected.get(i).x && row.y == expected.get(i).y && row.z == expected.get(i).z && Objects.equals(row.actorUuid, expected.get(i).actorUuid) && row.source.equals(expected.get(i).source), "pipeline changed quantity/order/actor/source/address");
            check(LogText.toChatLine(level, row).getString().contains(Long.toString(summary.amount())), "rendered ME line truncated the native amount");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            var context = Ae2TransferDetails.context(replay); check(context != null && replay.beAfter.equals(row.beAfter), "native evidence lost during replay");
            if (summary.kind().equals("f")) check(fluidKey.equals(AEKey.fromTagGeneric(level.registryAccess(), context.getCompound("Key"))), "fluid secondary components did not reconstruct exactly");
            else check(NbtSerde.readItemStack(row.itemStackNbt, level.registryAccess()).getHoverName().getString().equals("native ME item components"), "item components lost");
        }
    }
}
