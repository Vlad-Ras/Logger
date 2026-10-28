package com.roften.avilixlogger.smoke;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.implementations.blockentities.PatternContainerGroup;
import appeng.api.inventories.InternalInventory;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.*;
import appeng.core.definitions.*;
import appeng.helpers.InventoryAction;
import appeng.helpers.patternprovider.*;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.parts.reporting.PatternAccessTerminalPart;
import appeng.util.inv.AppEngInternalInventory;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.compat.ae2.mixin.PatternTrackerAccess;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Real remote action bodies/hooks with a data-only inspector; production emit remains unauthorized. */
public final class Ae2RemoteNativeSmoke {
    private static final RuntimeException FAULT = new RuntimeException("intentional remote native mutation failure");
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private static Field field(Class<?> type, String name) throws Exception { var f = type.getDeclaredField(name); f.setAccessible(true); return f; }
    private static final String FRAME = "avilixlogger$remote";
    private record Target(PatternProviderLogicHost host, InternalInventory inventory, Object tracker, long id) {}
    private static ItemStack pattern(Item input, Item output) {
        return PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(input), 2)), List.of(new GenericStack(AEItemKey.of(output), 3)));
    }
    public static void run(ServerLevel level, ServerPlayer player) throws Exception {
        check(!Ae2Audit.enabled() && Ae2RemoteAudit.begin(null, false) == null, "remote fixture enabled production capture");
        var previousMenu = player.containerMenu; var previousConnection = player.connection;
        var inventoryBefore = new ArrayList<ItemStack>(); for (var stack : player.getInventory().items) inventoryBefore.add(stack.copy());
        var positions = new ArrayList<BlockPos>(); var rows = new ArrayList<LogEntry>();
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(), new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public boolean isAcceptingMessages() { return true; }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener callback) {}
        };
        try {
            var terminalPos = position(positions, 0);
            var terminal = (PatternAccessTerminalPart)part(level, player, terminalPos, AEParts.PATTERN_ACCESS_TERMINAL.asItem());
            var menu = new PatternAccessTermMenu(110, player.getInventory(), terminal); player.containerMenu = menu;
            Target a = target(level, positions, menu, 1, false), b = target(level, positions, menu, 2, true);
            var first = pattern(Items.STONE, Items.OAK_PLANKS); var second = pattern(Items.DIRT, Items.STICK);
            menu.setCarried(first.copy()); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, a.id), 1, 1);
            check(ItemStack.isSameItemSameComponents(a.inventory.getStackInSlot(0), first), "native remote insert failed");
            menu.setCarried(second.copy()); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, a.id), 2, 1);
            check(ItemStack.isSameItemSameComponents(menu.getCarried(), first), "native remote swap changed cursor result");
            menu.setCarried(new ItemStack(Items.DIRT)); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, a.id), 0, -1);
            check(ItemStack.isSameItemSameComponents(a.inventory.getStackInSlot(0), second), "rejected pattern swap changed contents");
            menu.setCarried(ItemStack.EMPTY); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, a.id), 1, 1);
            menu.setCarried(first.copyWithCount(5)); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.SPLIT_OR_PLACE_SINGLE, 1, b.id), 1, 1);
            check(menu.getCarried().getCount() == 4 && b.inventory.getStackInSlot(1).getCount() == 1, "native single pattern placement changed");
            b.inventory.setItemDirect(1, first.copyWithCount(5)); menu.setCarried(ItemStack.EMPTY);
            menu.doAction(player, InventoryAction.SPLIT_OR_PLACE_SINGLE, 1, b.id);
            int nativeTaken = menu.getCarried().getCount(), nativeRemaining = b.inventory.getStackInSlot(1).getCount();
            b.inventory.setItemDirect(1, first.copyWithCount(5)); menu.setCarried(ItemStack.EMPTY);
            observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.SPLIT_OR_PLACE_SINGLE, 1, b.id), 1, 1);
            check(menu.getCarried().getCount() == nativeTaken && b.inventory.getStackInSlot(1).getCount() == nativeRemaining
                    && nativeTaken == Math.min(3, first.getMaxStackSize()) && rows.getLast().count == nativeTaken, "native split cap/result changed");
            player.getInventory().clearContent(); menu.setCarried(ItemStack.EMPTY);
            observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.SHIFT_CLICK, 1, b.id), 1, 1);
            check(b.inventory.getStackInSlot(1).isEmpty(), "native shift extraction failed");
            player.getInventory().clearContent(); a.inventory.setItemDirect(0, first.copyWithCount(2)); a.inventory.setItemDirect(1, second.copyWithCount(3));
            observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.MOVE_REGION, 0, a.id), 2, -1);
            check(a.inventory.getStackInSlot(0).isEmpty() && a.inventory.getStackInSlot(1).isEmpty(), "region did not preserve native live-stack mutation");
            a.inventory.setItemDirect(0, first.copy()); menu.setCarried(ItemStack.EMPTY);
            boolean creative = player.getAbilities().instabuild; player.getAbilities().instabuild = true;
            try { observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.CREATIVE_DUPLICATE, 0, a.id), 0, 0); }
            finally { player.getAbilities().instabuild = creative; }
            check(!menu.getCarried().isEmpty() && !a.inventory.getStackInSlot(0).isEmpty(), "native creative duplicate changed storage");
            menu.setCarried(ItemStack.EMPTY); observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, -1, a.id), 0, 0);
            observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, 123456789L), 0, 0);
            a.inventory.clear(); b.inventory.clear(); player.getInventory().clearContent();
            boolean quickMove = Arrays.stream(PatternAccessTermMenu.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("quickMovePattern"));
            if (quickMove) {
                int sourceSlot = playerSlot(menu); menu.slots.get(sourceSlot).set(second.copy());
                observed(menu, false, rows, () -> quickMove(menu, player, sourceSlot, List.of(b.id)), 1, 1);
                check(menu.slots.get(sourceSlot).getItem().isEmpty() && !b.inventory.getStackInSlot(0).isEmpty(), "native quick move failed");
                menu.slots.get(sourceSlot).set(second.copyWithCount(2));
                observed(menu, false, rows, () -> quickMove(menu, player, sourceSlot, List.of(b.id)), 0, 0);
                for (int i = 0; i < b.inventory.size(); i++) b.inventory.setItemDirect(i, first.copyWithCount(64));
                menu.slots.get(sourceSlot).set(second.copy());
                observed(menu, false, rows, () -> quickMove(menu, player, sourceSlot, List.of(b.id)), 0, 0);
                var ids = new ArrayList<Long>();
                for (int i = 0; i < 128; i++) {
                    var full = new AppEngInternalInventory(1); full.setItemDirect(0, first.copyWithCount(64));
                    ids.add(register(menu, a.host, full));
                }
                var poison = new InternalInventory() {
                    boolean armed;
                    public int size() { if (armed) throw new AssertionError("unselected provider was scanned"); return 1; }
                    public ItemStack getStackInSlot(int slot) { throw new AssertionError("unselected provider was read"); }
                    public void setItemDirect(int slot, ItemStack stack) { throw new AssertionError("unselected provider was mutated"); }
                };
                register(menu, a.host, poison); poison.armed = true;
                a.inventory.clear(); ids.add(a.id); menu.slots.get(sourceSlot).set(second.copy());
                observed(menu, false, rows, () -> quickMove(menu, player, sourceSlot, ids), 1, 1);
                check(menu.slots.get(sourceSlot).getItem().isEmpty() && !a.inventory.getStackInSlot(0).isEmpty(), "native full-provider fallback failed");
            }
            a.inventory.setItemDirect(0, second.copy());
            int[] writes = {0};
            var failing = new InternalInventory() {
                public int size() { return a.inventory.size(); }
                public ItemStack getStackInSlot(int slot) { return a.inventory.getStackInSlot(slot); }
                public void setItemDirect(int slot, ItemStack stack) { writes[0]++; a.inventory.setItemDirect(slot, stack); throw FAULT; }
            };
            long failedId = register(menu, a.host, failing); menu.setCarried(ItemStack.EMPTY);
            observed(menu, false, rows, () -> { try { menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, failedId); throw new AssertionError("native failure was swallowed"); } catch (RuntimeException failure) { check(failure == FAULT, "native failure replaced"); } }, 1, 1);
            check(writes[0] == 1 && a.inventory.getStackInSlot(0).isEmpty(), "native failure repeated or lost mutation");
            a.inventory.setItemDirect(0, first.copy());
            var changingMenu = new InternalInventory() {
                public int size() { return a.inventory.size(); }
                public ItemStack getStackInSlot(int slot) { return a.inventory.getStackInSlot(slot); }
                public void setItemDirect(int slot, ItemStack stack) { a.inventory.setItemDirect(slot, stack); player.containerMenu = player.inventoryMenu; }
            };
            long switchedId = register(menu, a.host, changingMenu); menu.setCarried(ItemStack.EMPTY);
            observed(menu, false, rows, () -> menu.doAction(player, InventoryAction.PICKUP_OR_SET_DOWN, 0, switchedId), 1, 1);
            check(player.containerMenu == player.inventoryMenu, "native callback did not switch menu"); player.containerMenu = menu;
            var simulated = Ae2RemoteAudit.inspect(menu, false); var simulatedInventory = simulated.wrap(a.inventory, a.host, a.id);
            check(simulatedInventory.simulateAdd(first.copy()).isEmpty() && a.inventory.isEmpty() && simulated.retainedSlots() == 0, "simulated insert changed or retained state");
            a.inventory.setItemDirect(0, first.copy());
            check(!simulatedInventory.extractItem(0, 1, true).isEmpty() && !a.inventory.getStackInSlot(0).isEmpty()
                    && simulated.retainedSlots() == 0 && simulated.finish().isEmpty(), "simulated extraction produced evidence");
            disabledWrappers(menu, player);
            var offThread = new java.util.concurrent.atomic.AtomicReference<Ae2RemoteAudit.Capture>();
            var thread = new Thread(() -> offThread.set(Ae2RemoteAudit.inspect(menu, true))); thread.start(); thread.join(5000);
            check(!thread.isAlive() && offThread.get() == null, "off-thread remote inspector read state");
            pipeline(level, player, terminalPos, rows);
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_REMOTE_NATIVE_OK: real remote insert/swap/rejection/split/shift/live region/creative/invalid actions, native partial failure once, original target+terminal, worker/replay; quickMove={}, fullCandidates={}, rows={}; inspector-only, authenticated emit not tested", quickMove, quickMove ? 128 : 0, rows.size());
        } finally {
            player.containerMenu = previousMenu; player.connection = previousConnection;
            player.getInventory().clearContent(); for (int i = 0; i < inventoryBefore.size(); i++) player.getInventory().items.set(i, inventoryBefore.get(i));
            for (var pos : positions) level.removeBlock(pos, false);
        }
    }
    private static Target target(ServerLevel level, List<BlockPos> positions, PatternAccessTermMenu menu, int index, boolean part) throws Exception {
        var pos = position(positions, index); PatternProviderLogicHost host;
        if (part) host = (PatternProviderLogicHost)part(level, (ServerPlayer)menu.getPlayer(), pos, AEParts.PATTERN_PROVIDER.asItem());
        else { level.setBlockAndUpdate(pos, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState()); host = (PatternProviderLogicHost)level.getBlockEntity(pos); }
        var inventory = host.getTerminalPatternInventory(); long id = register(menu, host, inventory);
        return new Target(host, inventory, trackers(menu).get(id), id);
    }
    private static it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Object> trackers(PatternAccessTermMenu menu) throws Exception {
        return (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<Object>)field(PatternAccessTermMenu.class, "byId").get(menu);
    }
    private static long register(PatternAccessTermMenu menu, PatternProviderLogicHost host, InternalInventory inventory) throws Exception {
        var type = Class.forName("appeng.menu.implementations.PatternAccessTermMenu$ContainerTracker");
        var constructor = type.getDeclaredConstructor(PatternContainer.class, InternalInventory.class, PatternContainerGroup.class); constructor.setAccessible(true);
        var group = new PatternContainerGroup(AEItemKey.of(AEBlocks.PATTERN_PROVIDER.asItem()), net.minecraft.network.chat.Component.literal("native remote test group"), List.of());
        var tracker = constructor.newInstance(host, inventory, group); check(tracker instanceof PatternTrackerAccess, "tracker accessor hooks missing");
        var access = (PatternTrackerAccess)tracker; check(access.avilixlogger$container() == host && access.avilixlogger$server() == inventory, "tracker accessor changed identity");
        long id = access.avilixlogger$serverId(); trackers(menu).put(id, tracker); return id;
    }
    static void observed(PatternAccessTermMenu menu, boolean liveReads, List<LogEntry> rows, Runnable action, int count, int retained) throws Exception {
        var frame = field(PatternAccessTermMenu.class, FRAME); check(frame.get(menu) == null, "remote scope leaked before action");
        var capture = Ae2RemoteAudit.inspect(menu, liveReads); check(capture != null, "remote data inspector unavailable"); frame.set(menu, capture);
        try {
            action.run(); check(frame.get(menu) == capture, "nested native action closed inspector owner");
            if (retained >= 0) check(capture.retainedSlots() == retained, "remote action retained unnecessary slot snapshots: " + capture.retainedSlots());
            frame.set(menu, null); var changes = capture.finish();
            check(changes.size() == count, "remote expected " + count + " rows, got " + changes.size() + ": " + changes.stream().map(r -> r.type + "/" + r.source).toList());
            for (var row : changes) {
                int start = row.source.indexOf(":provider=") + ":provider=".length(), end = row.source.indexOf(":ENCODED_PATTERN:", start);
                var tracker = (PatternTrackerAccess)trackers(menu).get(Long.parseLong(row.source.substring(start, end)));
                var provider = (PatternProviderLogicHost)tracker.avilixlogger$container(); var block = provider.getBlockEntity();
                check(row.x == block.getBlockPos().getX() && row.y == block.getBlockPos().getY() && row.z == block.getBlockPos().getZ()
                        && row.dim.equals(block.getLevel().dimension().location().toString()), "remote provider ID/address mismatch");
            }
            rows.addAll(changes);
            check(capture.retainedSlots() == 0 && capture.finish().isEmpty(), "remote finish leaked snapshots or repeated rows");
        } finally { frame.set(menu, null); capture.finish(); }
    }
    private static void disabledWrappers(PatternAccessTermMenu menu, ServerPlayer player) throws Exception {
        int tested = 0;
        for (Method method : PatternAccessTermMenu.class.getDeclaredMethods()) if ((method.getName().contains("avilixlogger$remoteAction") || method.getName().contains("avilixlogger$remoteMove"))
                && method.getParameterCount() > 0 && method.getParameterTypes()[method.getParameterCount() - 1] == Operation.class) {
            method.setAccessible(true); int[] calls = {0}; Operation<Void> original = args -> { calls[0]++; throw FAULT; };
            try {
                if (method.getName().contains("remoteAction")) method.invoke(menu, player, InventoryAction.SHIFT_CLICK, 0, 0L, original);
                else method.invoke(menu, player, 0, List.of(), original);
                throw new AssertionError("wrapper swallowed failure");
            } catch (InvocationTargetException failure) { check(failure.getCause() == FAULT, "wrapper replaced original failure"); }
            check(calls[0] == 1 && field(PatternAccessTermMenu.class, FRAME).get(menu) == null, "disabled wrapper repeated or leaked scope"); tested++;
        }
        int expected = Arrays.stream(PatternAccessTermMenu.class.getDeclaredMethods()).anyMatch(m -> m.getName().equals("quickMovePattern")) ? 2 : 1;
        check(tested == expected, "missing transformed remote wrappers");
    }
    private static void quickMove(PatternAccessTermMenu menu, ServerPlayer player, int slot, List<Long> ids) {
        try { PatternAccessTermMenu.class.getDeclaredMethod("quickMovePattern", ServerPlayer.class, int.class, List.class).invoke(menu, player, slot, ids); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException original) throw original;
            if (failure.getCause() instanceof Error original) throw original;
            throw new AssertionError(failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }
    private static int playerSlot(AEBaseMenu menu) { for (int i = 0; i < menu.slots.size(); i++) if (menu.getSlotSemantic(menu.slots.get(i)) != null && menu.getSlotSemantic(menu.slots.get(i)).playerSide()) return i; throw new AssertionError("native player slot missing"); }
    private static BlockPos position(List<BlockPos> positions, int index) { var pos = new BlockPos(180 + index * 4, 100, 180); positions.add(pos); return pos; }
    private static Object part(ServerLevel level, ServerPlayer player, BlockPos pos, Item item) {
        level.setBlockAndUpdate(pos, AEBlocks.CABLE_BUS.block().defaultBlockState()); var host = (IPartHost)level.getBlockEntity(pos);
        var added = host.addPart((appeng.api.parts.IPartItem<?>)item, Direction.NORTH, player); check(added != null, "remote part missing"); return added;
    }
    private static void pipeline(ServerLevel level, ServerPlayer player, BlockPos terminal, List<LogEntry> rows) {
        var received = new CopyOnWriteArrayList<LogEntry>(); var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery query) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery query) { return query(query); }
            public void shutdown() {}
        }, 8, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == rows.size(), "remote pipeline lost rows");
        for (int i = 0; i < rows.size(); i++) {
            var row = received.get(i); check(row.source.equals(rows.get(i).source) && row.type == rows.get(i).type && row.actorUuid.equals(player.getUUID()) && row.actorName.equals(player.getGameProfile().getName()), "remote FIFO/actor lost");
            check(row.x != terminal.getX() && row.source.contains("terminal=" + level.dimension().location() + "@" + terminal.getX() + ',' + terminal.getY() + ',' + terminal.getZ()) && row.source.contains(":ENCODED_PATTERN:slot="), "remote record used terminal as provider position");
            check(Ae2TransferDetails.context(row) != null && Ae2TransferDetails.context(row).getString("Domain").equals("device-slot") && row.itemStackNbt != null && row.count > 0, "remote exact pattern payload lost");
            var nativeItem = ItemStack.parseOptional(level.registryAccess(), NbtSerde.fromSnbt(row.itemStackNbt));
            check(PatternDetailsHelper.decodePattern(nativeItem, level) != null, "remote serialized pattern lost native components");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(replay.source.equals(row.source) && replay.beAfter.equals(row.beAfter) && replay.itemStackNbt.equals(row.itemStackNbt), "remote replay lost terminal/provider/components");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-transfer-v1"), "remote GUI leaked evidence prefix");
        }
    }
}
