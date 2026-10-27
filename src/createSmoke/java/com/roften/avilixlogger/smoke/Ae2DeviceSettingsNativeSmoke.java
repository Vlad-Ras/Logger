package com.roften.avilixlogger.smoke;

import appeng.api.config.*;
import appeng.api.parts.*;
import appeng.api.stacks.*;
import appeng.api.util.IConfigurableObject;
import appeng.core.definitions.*;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.helpers.*;
import appeng.menu.*;
import appeng.menu.implementations.*;
import appeng.menu.slot.*;
import appeng.parts.AEBasePart;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Concrete native menu actions + detached rows, with production capture still unauthorized. */
public final class Ae2DeviceSettingsNativeSmoke {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke");
    private static final RuntimeException FAULT = new RuntimeException("intentional configuration wrapper failure");
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private record Device(String name, AEBaseMenu menu, BlockPos pos) {}

    public static void run(ServerLevel level, ServerPlayer player) throws Exception {
        check(!Ae2Audit.enabled(), "device fixture bypassed authorization");
        var positions = new ArrayList<BlockPos>(); var devices = new ArrayList<Device>(); var rows = new ArrayList<LogEntry>();
        var previousMenu = player.containerMenu; var previousConnection = player.connection;
        if (player.connection == null) player.connection = connection(level, player);
        try {
            var pos = position(positions, 0);
            var storage = part(level, player, pos, AEParts.STORAGE_BUS.asItem());
            devices.add(new Device("storage bus", new StorageBusMenu(StorageBusMenu.TYPE, 70, player.getInventory(), storage), pos));
            pos = position(positions, 1); var importer = part(level, player, pos, AEParts.IMPORT_BUS.asItem());
            devices.add(new Device("import bus", new IOBusMenu(IOBusMenu.IMPORT_TYPE, 71, player.getInventory(), importer), pos));
            pos = position(positions, 2); var exporter = part(level, player, pos, AEParts.EXPORT_BUS.asItem());
            devices.add(new Device("export bus", new IOBusMenu(IOBusMenu.EXPORT_TYPE, 72, player.getInventory(), exporter), pos));
            pos = position(positions, 3); var plane = part(level, player, pos, AEParts.FORMATION_PLANE.asItem());
            devices.add(new Device("formation plane", new FormationPlaneMenu(FormationPlaneMenu.TYPE, 73, player.getInventory(), plane), pos));
            pos = position(positions, 4); var emitter = part(level, player, pos, AEParts.LEVEL_EMITTER.asItem());
            var emitterMenu = new StorageLevelEmitterMenu(StorageLevelEmitterMenu.TYPE, 74, player.getInventory(), emitter);
            devices.add(new Device("storage level emitter", emitterMenu, pos));
            pos = position(positions, 5); level.setBlockAndUpdate(pos, AEBlocks.INTERFACE.block().defaultBlockState());
            devices.add(new Device("interface", new InterfaceMenu(InterfaceMenu.TYPE, 75, player.getInventory(), (InterfaceLogicHost)level.getBlockEntity(pos)), pos));
            pos = position(positions, 6); level.setBlockAndUpdate(pos, AEBlocks.DRIVE.block().defaultBlockState());
            devices.add(new Device("drive", new DriveMenu(76, player.getInventory(), (appeng.blockentity.storage.DriveBlockEntity)level.getBlockEntity(pos)), pos));
            pos = position(positions, 7); level.setBlockAndUpdate(pos, AEBlocks.ME_CHEST.block().defaultBlockState());
            devices.add(new Device("ME chest", new MEChestMenu(77, player.getInventory(), (appeng.blockentity.storage.MEChestBlockEntity)level.getBlockEntity(pos)), pos));
            pos = position(positions, 8); level.setBlockAndUpdate(pos, AEBlocks.CELL_WORKBENCH.block().defaultBlockState());
            devices.add(new Device("cell workbench", new CellWorkbenchMenu(78, player.getInventory(), (appeng.blockentity.misc.CellWorkbenchBlockEntity)level.getBlockEntity(pos)), pos));
            int filters = 0, upgrades = 0, cells = 0, settings = 0;
            for (var device : devices) {
                var menu = device.menu(); player.containerMenu = menu; player.setPos(device.pos().getX()+2.5, 100.5, device.pos().getZ()+.5);
                check(menu instanceof Ae2MenuCapture, "optional hooks missing on " + device.name());
                var start = snapshot(menu); check(Ae2Audit.changes(menu, start, start).isEmpty(), "no-op menu produced rows");
                int filter = find(menu, "CONFIG");
                if (filter >= 0 && menu.slots.get(filter) instanceof FakeSlot) {
                    var first = new ItemStack(Items.OAK_SAPLING, 7); first.set(DataComponents.CUSTOM_NAME, Component.literal("native filter A"));
                    rows.addAll(mutate(device, player, () -> menu.setFilter(filter, first), ActionType.ME_FILTER_CHANGE));
                    var next = first.copy(); next.set(DataComponents.CUSTOM_NAME, Component.literal("native filter B"));
                    rows.addAll(mutate(device, player, () -> menu.setFilter(filter, next), ActionType.ME_FILTER_CHANGE));
                    rows.addAll(mutate(device, player, () -> menu.setFilter(filter, ItemStack.EMPTY), ActionType.ME_FILTER_CHANGE)); filters++;
                }
                int upgrade = find(menu, "UPGRADE");
                if (upgrade >= 0) {
                    ItemStack card = null;
                    for (var candidate : List.of(AEItems.CAPACITY_CARD.stack(), AEItems.SPEED_CARD.stack(), AEItems.FUZZY_CARD.stack(), AEItems.REDSTONE_CARD.stack()))
                        if (menu.slots.get(upgrade).mayPlace(candidate)) { card = candidate; break; }
                    check(card != null, "no accepted native upgrade for " + device.name());
                    ItemStack accepted = card;
                    rows.addAll(mutate(device, player, () -> { menu.setCarried(accepted.copy()); menu.clicked(upgrade, 0, ClickType.PICKUP, player); }, ActionType.ME_UPGRADE_CHANGE));
                    rows.addAll(mutate(device, player, () -> { menu.setCarried(ItemStack.EMPTY); menu.clicked(upgrade, 0, ClickType.PICKUP, player); }, ActionType.ME_UPGRADE_CHANGE));
                    menu.setCarried(ItemStack.EMPTY); upgrades++;
                }
                int cell = find(menu, "STORAGE_CELL");
                if (cell >= 0) {
                    var first = AEItems.ITEM_CELL_1K.stack(); first.set(DataComponents.CUSTOM_NAME, Component.literal("native cell A"));
                    check(menu.slots.get(cell).mayPlace(first), "native cell slot refused supported cell");
                    rows.addAll(mutate(device, player, () -> { menu.setCarried(first.copy()); menu.clicked(cell, 0, ClickType.PICKUP, player); }, ActionType.ME_CELL_CHANGE));
                    var replacement = first.copy(); replacement.set(DataComponents.CUSTOM_NAME, Component.literal("native cell B"));
                    rows.addAll(mutate(device, player, () -> { menu.setCarried(replacement); menu.clicked(cell, 0, ClickType.PICKUP, player); }, ActionType.ME_CELL_CHANGE));
                    if (menu instanceof CellWorkbenchMenu) rows.addAll(mutate(device, player, () -> menu.receiveClientAction("setFuzzyMode", "\"PERCENT_50\""), ActionType.ME_CELL_CHANGE));
                    rows.addAll(mutate(device, player, () -> { menu.setCarried(ItemStack.EMPTY); menu.clicked(cell, 0, ClickType.PICKUP, player); }, ActionType.ME_CELL_CHANGE));
                    menu.setCarried(ItemStack.EMPTY); cells++;
                }
                if (menu.getTarget() instanceof IConfigurableObject target) {
                    var configurable = target.getConfigManager();
                    var setting = configurable.getSettings().stream().filter(s -> s.getValues().size() > 1).findFirst().orElse(null);
                    if (setting != null) { rows.addAll(mutate(device, player, () -> new ConfigButtonPacket(setting, true).handleOnServer(player), ActionType.ME_SETTING_CHANGE)); settings++; }
                }
                LOG.info("AE2_DEVICE_MENU_CHECKED: {} native slots/filter/click/settings; detached state comparison, production unauthorized", device.name());
            }
            var priorityMenu = new PriorityMenu(79, player.getInventory(), storage); player.containerMenu = priorityMenu;
            var priorityDevice = new Device("storage bus priority", priorityMenu, devices.getFirst().pos());
            var priorityRows = mutate(priorityDevice, player, () -> priorityMenu.receiveClientAction("setPriority", "-73"), ActionType.ME_SETTING_CHANGE); rows.addAll(priorityRows);
            check(storage.getPriority() == -73 && after(priorityRows.getFirst()).getString("Value").equals("-73"), "priority snapshot used wrong/cached value");
            check(mutate(priorityDevice, player, () -> priorityMenu.receiveClientAction("setPriority", "-73"), null).isEmpty(), "unchanged priority logged");
            player.containerMenu = emitterMenu; long threshold = (1L << 40) + 13;
            var emitterDevice = devices.get(4);
            var thresholdRows = mutate(emitterDevice, player, () -> emitterMenu.receiveClientAction("setReportingValue", Long.toString(threshold)), ActionType.ME_SETTING_CHANGE); rows.addAll(thresholdRows);
            check(emitter.getReportingValue() == threshold && after(thresholdRows.getFirst()).getString("Value").equals(Long.toString(threshold)), "threshold truncated or read client cache");
            check(mutate(emitterDevice, player, () -> emitterMenu.receiveClientAction("setReportingValue", Long.toString(threshold)), null).isEmpty(), "unchanged threshold logged");
            wrapper(priorityMenu, "avilixlogger$priority", -73); wrapper(emitterMenu, "avilixlogger$threshold", threshold);
            var absent = new Ae2Audit.Snapshot(Map.of(), Map.of("removed", "old"));
            var added = new Ae2Audit.Snapshot(Map.of(), Map.of("added", ""));
            var presence = Ae2Audit.changes(emitterMenu, absent, added); check(presence.size() == 2, "setting additions/removals ignored"); rows.addAll(presence);
            check(filters >= 4 && upgrades >= 3 && cells >= 3 && settings >= 3, "concrete menu coverage unexpectedly missing: " + filters + '/' + upgrades + '/' + cells + '/' + settings);
            pipeline(level, rows);
            LOG.info("AE2_DEVICE_SETTINGS_NATIVE_OK: nine concrete devices + priority submenu; filterCases={}, upgradeCases={}, cellCases={}, settingsCases={}, rows={}; full before/after, native values, long threshold and wrapper cleanup; authenticated emit not tested", filters, upgrades, cells, settings, rows.size());
        } finally {
            player.containerMenu = previousMenu; player.connection = previousConnection;
            for (var pos : positions) level.removeBlock(pos, false);
        }
    }
    private static BlockPos position(List<BlockPos> positions, int index) { var pos = new BlockPos(80 + index*4, 100, 80); positions.add(pos); return pos; }
    private static <T extends AEBasePart> T part(ServerLevel level, ServerPlayer player, BlockPos pos, IPartItem<T> item) {
        level.setBlockAndUpdate(pos, AEBlocks.CABLE_BUS.block().defaultBlockState());
        var host = (IPartHost)Objects.requireNonNull(level.getBlockEntity(pos)); var part = host.addPart(item, Direction.NORTH, player);
        check(part != null, "native cable bus did not install part"); return part;
    }
    private static Ae2Audit.Snapshot snapshot(AEBaseMenu menu) { var result = Ae2Audit.snapshot(menu); check(result != null, "native menu snapshot failed: " + menu.getClass()); return result; }
    private static int find(AEBaseMenu menu, String semantic) {
        for (int i=0;i<menu.slots.size();i++) if (menu.getSlotSemantic(menu.slots.get(i)) != null && menu.getSlotSemantic(menu.slots.get(i)).id().equals(semantic)
                && (!(menu.slots.get(i) instanceof AppEngSlot slot) || slot.isSlotEnabled())) return i;
        return -1;
    }
    private static List<LogEntry> mutate(Device device, ServerPlayer player, Runnable action, ActionType expected) {
        var before = snapshot(device.menu()); action.run(); var after = snapshot(device.menu());
        var changes = Ae2Audit.changes(device.menu(), before, after);
        if (expected == null) check(changes.isEmpty(), "no-op action produced rows on " + device.name());
        else check(changes.stream().filter(row -> row.type == expected).count() == 1, "native action missing/duplicated " + expected + " on " + device.name() + ": " + changes.stream().map(row -> row.type).toList());
        for (var row : changes) check(row.actorUuid.equals(player.getUUID()) && row.dim.equals(player.level().dimension().location().toString())
                && row.x == device.pos().getX() && row.y == device.pos().getY() && row.z == device.pos().getZ(), "native menu actor/address lost");
        try { check(depth(device.menu()) == 0, "native action retained nested capture"); } catch (Exception failure) { throw new AssertionError(failure); }
        return changes;
    }
    private static CompoundTag after(LogEntry row) { return (CompoundTag) row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER); }
    private static int depth(AEBaseMenu menu) throws Exception { var field = AEBaseMenu.class.getDeclaredField("avilixlogger$depth"); field.setAccessible(true); return field.getInt(menu); }
    private static void wrapper(AEBaseMenu menu, String name, Object value) throws Exception {
        var method = Arrays.stream(menu.getClass().getDeclaredMethods()).filter(m -> m.getName().contains(name) && m.getParameterCount() == 2 && m.getParameterTypes()[1] == Operation.class).findFirst().orElseThrow();
        method.setAccessible(true); int[] calls = {0};
        Operation<Object> original = args -> { calls[0]++; check(args.length == 1 && args[0].equals(value), "setter argument changed"); throw FAULT; };
        try { method.invoke(menu, value, original); throw new AssertionError("setter exception swallowed"); }
        catch (InvocationTargetException failure) { check(failure.getCause() == FAULT, "setter exception replaced"); }
        check(calls[0] == 1 && depth(menu) == 0, "setter repeated original action or retained capture");
    }
    public static void checkStock(AEBaseMenu menu, ItemStack before, ItemStack after) {
        var row = Ae2Audit.configurationRow(menu, "CONFIG", 0, before, after); check(row != null, "stock pair missing");
        var old = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_BEFORE); var now = after(row);
        check(old.getLong("Amount") == 8 && now.getLong("Amount") == 32, "stock wrapper amount not preserved on both sides");
        pipeline((ServerLevel)menu.getPlayer().level(), List.of(row));
        LOG.info("AE2_STOCK_CONFIGURATION_NATIVE_OK: native wrapper quantity 8/32, exact item/component before/after and original target retained");
    }
    private static void pipeline(ServerLevel level, List<LogEntry> rows) {
        var received = new CopyOnWriteArrayList<LogEntry>(); var sink = new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        };
        var storage = new AsyncLogStorage(sink, 8, 1024*1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == rows.size(), "device pipeline lost/duplicated rows");
        for (int i=0;i<received.size();i++) {
            var row = received.get(i); check(row.type == rows.get(i).type && row.source.equals(rows.get(i).source) && row.actorUuid.equals(rows.get(i).actorUuid), "device FIFO/metadata lost");
            if (Ae2ConfigurationDetails.configuration(row)) {
                check(Ae2ConfigurationDetails.context(row) != null, "complete device pair missing after worker");
                check(!LogText.toChatLine(level, row).getString().contains("ae2-config-v1|"), "internal configuration prefix leaked into UI");
                var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
                check(row.beBefore.equals(replay.beBefore) && row.beAfter.equals(replay.beAfter), "device Gzip replay lost native states");
            }
        }
    }
    private static net.minecraft.server.network.ServerGamePacketListenerImpl connection(ServerLevel level, ServerPlayer player) {
        return new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(), new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public boolean isAcceptingMessages() { return true; }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener callback) {}
        };
    }
}
