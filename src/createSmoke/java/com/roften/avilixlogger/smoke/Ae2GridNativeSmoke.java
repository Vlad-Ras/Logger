package com.roften.avilixlogger.smoke;

import appeng.api.config.*;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.*;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.*;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.core.definitions.*;
import appeng.core.network.clientbound.*;
import appeng.core.network.serverbound.InventoryActionPacket;
import appeng.helpers.InventoryAction;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.implementations.PatternAccessTermMenu;
import appeng.parts.AEBasePart;
import appeng.parts.reporting.PatternAccessTerminalPart;
import appeng.api.crafting.PatternDetailsHelper;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.compat.ae2.mixin.PatternTrackerAccess;
import com.roften.avilixlogger.core.*;
import io.netty.buffer.Unpooled;
import net.minecraft.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;

/** Powered native grid and wire payloads, across actual server ticks. No production authorization. */
@EventBusSubscriber(modid="avilixlogger")
public final class Ae2GridNativeSmoke {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke");
    private static Fixture active;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void start(ServerLevel level) throws Exception {
        check(active == null, "grid fixture already running");
        var fixture = new Fixture(level);
        try { fixture.setup(); active = fixture; }
        catch (Throwable failure) { fixture.close(); throw failure; }
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var fixture = active;
        if (fixture == null) return;
        boolean done = false;
        try { done = fixture.tick(); }
        catch (Throwable failure) { LOG.error("AE2_NATIVE_FAILED: powered grid", failure); done = true; }
        finally {
            if (done) {
                active = null;
                try { fixture.close(); } finally { fixture.level.getServer().halt(false); }
            }
        }
    }
    private static final class Fixture {
        final ServerLevel level;
        final List<BlockPos> positions = new ArrayList<>();
        final List<IManagedGridNode> nodes = new ArrayList<>();
        final List<LogEntry> rows = new ArrayList<>();
        // A detached receiver model, not an instance of the client screen.
        final Map<Long, List<ItemStack>> mirror = new HashMap<>();
        final List<PatternAccessTerminalPacket> updates = new ArrayList<>();
        ServerPlayer player;
        PatternAccessTerminalPart terminal;
        PatternProviderLogicHost block, part;
        IManagedGridNode power;
        appeng.blockentity.crafting.CraftingBlockEntity craftingBlock;
        IGridConnection terminalConnection;
        PatternAccessTermMenu menu;
        int ticks, phase, clears, wirePackets, saturationCases;
        boolean wasForced;
        long blockId, partId;
        Ae2AssemblyNativeSmoke assembly;
        Ae2ProcessingNativeSmoke processing;
        Fixture(ServerLevel level) { this.level = level; }
        BlockPos position(int x) { var p = new BlockPos(32 + x, 110, 32); positions.add(p); return p; }
        void setup() {
            wasForced = level.getForcedChunks().contains(net.minecraft.world.level.ChunkPos.asLong(2, 2));
            level.setChunkForced(2, 2, true);
            player = new ServerPlayer(level.getServer(), level,
                    new com.mojang.authlib.GameProfile(UUID.randomUUID(), "Ae2GridEditor"), ClientInformation.createDefault());
            player.setPos(32, 110, 32);
            player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override public boolean isAcceptingMessages() { return true; }
                @Override public void send(Packet<?> packet) { receive(packet); }
                @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener callback) { receive(packet); }
            };
            terminal = (PatternAccessTerminalPart)installPart(position(0), AEParts.PATTERN_ACCESS_TERMINAL.asItem());
            var blockPos = position(3);
            level.setBlockAndUpdate(blockPos, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
            block = (PatternProviderLogicHost)Objects.requireNonNull(level.getBlockEntity(blockPos));
            part = (PatternProviderLogicHost)installPart(position(6), AEParts.PATTERN_PROVIDER.asItem());
            var powerPos = position(9);
            level.setBlockAndUpdate(powerPos, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
            power = ((AENetworkedBlockEntity)Objects.requireNonNull(level.getBlockEntity(powerPos))).getMainNode();
            nodes.add(terminal.getMainNode()); nodes.add(((AENetworkedBlockEntity)block).getMainNode());
            nodes.add(((AEBasePart)part).getMainNode()); nodes.add(power);
            var craftingPos = position(12);
            level.setBlockAndUpdate(craftingPos, AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
            craftingBlock = (appeng.blockentity.crafting.CraftingBlockEntity)Objects.requireNonNull(level.getBlockEntity(craftingPos));
            nodes.add(craftingBlock.getMainNode());
            menu = new PatternAccessTermMenu(117, player.getInventory(), terminal); player.containerMenu = menu;
            check(!Ae2Audit.enabled(), "grid fixture bypassed production authorization");
        }
        Object installPart(BlockPos pos, Item item) {
            level.setBlockAndUpdate(pos, AEBlocks.CABLE_BUS.block().defaultBlockState());
            var host = (IPartHost)Objects.requireNonNull(level.getBlockEntity(pos));
            return Objects.requireNonNull(host.addPart((appeng.api.parts.IPartItem<?>)item, Direction.NORTH, player));
        }
        boolean tick() throws Exception {
            check(++ticks <= 4200, "powered grid did not reach phase " + phase + "; nodes=" + nodes.stream().map(n -> n.isReady() + "/" + n.isActive()).toList());
            if (phase == 5) {
                if (!assembly.tick()) return false;
                assembly.close(); assembly = null;
                processing = new Ae2ProcessingNativeSmoke(level, player, block, craftingBlock, power);
                processing.setup(); phase = 6; return false;
            }
            if (phase == 6) {
                if (!processing.tick()) return false;
                LOG.info("AE2_NATIVE_OK: selected menu/packet/scope, powered grid, real assembler, external processing and autonomous requester contracts; production authenticated emit remains open");
                return true;
            }
            if (phase == 0) {
                if (nodes.stream().anyMatch(n -> !n.isReady())) return false;
                // Explicit fixture links; remote provider discovery itself remains entirely native.
                for (int i : List.of(0, 1, 2, 4)) {
                    var connection = GridHelper.createConnection(nodes.get(i).getNode(), power.getNode());
                    if (i == 0) terminalConnection = connection;
                }
                phase = 1; return false;
            }
            if (phase == 1) {
                if (nodes.stream().anyMatch(n -> !n.isActive())) return false;
                if (craftingBlock.getCluster() == null || !terminal.getGridNode().getGrid().getCraftingService().getCpus().contains(craftingBlock.getCluster())) return false;
                Ae2CraftingNativeSmoke.run(level, player, terminal, craftingBlock);
                discoveredActions(); filters(); saturation();
                nodes.get(2).destroy(); level.removeBlock(part.getBlockEntity().getBlockPos(), false);
                phase = 2; return false;
            }
            if (phase == 2) {
                if (!terminal.getMainNode().isActive() || !nodes.get(1).isActive()) return false;
                broadcast();
                check(mirror.size() == 1 && !mirror.containsKey(partId), "removed provider survived full update");
                stale(partId); blockId = id(block);
                terminalConnection.destroy(); phase = 3; return false;
            }
            if (phase == 3) {
                if (terminal.getMainNode().isActive()) return false;
                broadcast(); check(mirror.isEmpty(), "unpowered terminal retained remote providers"); stale(blockId);
                terminalConnection = GridHelper.createConnection(terminal.getMainNode().getNode(), power.getNode());
                phase = 4; return false;
            }
            if (!terminal.getMainNode().isActive() || !nodes.get(1).isActive()) return false;
            broadcast(); long reconnected = id(block);
            check(reconnected != blockId && mirror.size() == 1 && mirror.containsKey(reconnected), "reconnected grid did not issue fresh provider ID");
            stale(blockId);
            menu.setCarried(pattern(Items.GOLD_INGOT));
            Ae2RemoteNativeSmoke.observed(menu, false, rows, () -> action(InventoryAction.PICKUP_OR_SET_DOWN, 2, reconnected), 1, 1);
            broadcast(); check(ItemStack.matches(mirror.get(reconnected).get(2), block.getTerminalPatternInventory().getStackInSlot(2)), "reconnected payload lost pattern");
            check(!Ae2Audit.enabled(), "grid fixture changed production authorization");
            LOG.info("AE2_GRID_NATIVE_OK: native powered discovery BE+part, wire full/incremental/empty, filters, removal/stale IDs, disconnect/reconnect; saturationCases={}, wirePackets={}, clears={}, rows={}, ticks={}; inspector-only, client rendering/authenticated emit not tested", saturationCases, wirePackets, clears, rows.size(), ticks);
            assembly = new Ae2AssemblyNativeSmoke(level, player, terminal, block, craftingBlock, power);
            assembly.setup(); phase = 5; return false;
        }
        void discoveredActions() throws Exception {
            broadcast(); check(mirror.size() == 2, "native grid did not discover both BE/part providers");
            blockId = id(block); partId = id(part);
            check(mirror.containsKey(blockId) && mirror.containsKey(partId), "discovery packet ID did not match native tracker");
            broadcast(); updates.clear();
            var encoded = pattern(Items.IRON_INGOT); menu.setCarried(encoded.copy());
            Ae2RemoteNativeSmoke.observed(menu, false, rows, () -> action(InventoryAction.PICKUP_OR_SET_DOWN, 0, partId), 1, 1);
            broadcast();
            check(updates.size() == 1 && !updates.getFirst().fullUpdate() && updates.getFirst().inventoryId() == partId
                    && ItemStack.matches(mirror.get(partId).get(0), encoded), "incremental remote insert payload changed ID/components");
            updates.clear(); menu.setCarried(ItemStack.EMPTY);
            Ae2RemoteNativeSmoke.observed(menu, false, rows, () -> action(InventoryAction.PICKUP_OR_SET_DOWN, 0, partId), 1, 1);
            broadcast(); check(updates.size() == 1 && updates.getFirst().slots().containsKey(0) && mirror.get(partId).get(0).isEmpty(), "incremental empty slot not sent");
        }
        void filters() throws Exception {
            long old = partId;
            part.getConfigManager().putSetting(Settings.PATTERN_ACCESS_TERMINAL, YesNo.NO);
            broadcast(); check(mirror.size() == 1, "hidden native provider remained visible"); stale(old);
            terminal.getConfigManager().putSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.ALL);
            broadcast(); check(mirror.size() == 2, "ALL did not rediscover hidden provider");
            terminal.getConfigManager().putSetting(Settings.TERMINAL_SHOW_PATTERN_PROVIDERS, ShowPatternProviders.VISIBLE);
            part.getConfigManager().putSetting(Settings.PATTERN_ACCESS_TERMINAL, YesNo.YES);
            broadcast(); blockId = id(block); partId = id(part);
        }
        void saturation() throws Exception {
            var inv = block.getTerminalPatternInventory();
            for (InventoryAction action : List.of(InventoryAction.SHIFT_CLICK, InventoryAction.MOVE_REGION)) {
                for (int free : List.of(0, 1, 2)) {
                    inv.clear(); inv.setItemDirect(0, pattern(Items.IRON_INGOT)); inv.setItemDirect(1, pattern(Items.GOLD_INGOT)); inv.setItemDirect(2, pattern(Items.DIAMOND));
                    for (int i = 0; i < player.getInventory().items.size(); i++) player.getInventory().items.set(i, i < free ? ItemStack.EMPTY : new ItemStack(Items.COBBLESTONE, 64));
                    menu.setCarried(ItemStack.EMPTY);
                    var before = snapshot(inv); var playerBefore = player.getInventory().items.stream().map(ItemStack::copy).toList();
                    action(action, 0, blockId); var baseline = snapshot(inv); var playerAfter = player.getInventory().items.stream().map(ItemStack::copy).toList();
                    for (int i = 0; i < before.size(); i++) inv.setItemDirect(i, before.get(i).copy());
                    for (int i = 0; i < playerBefore.size(); i++) player.getInventory().items.set(i, playerBefore.get(i).copy());
                    int expected = deltaRows(before, baseline);
                    int start = rows.size();
                    Ae2RemoteNativeSmoke.observed(menu, false, rows, () -> action(action, 0, blockId), expected, -1);
                    same(snapshot(inv), baseline, "observer changed saturated native provider result");
                    same(player.getInventory().items, playerAfter, "observer changed saturated native player result");
                    verifyDeltas(level, before, baseline, rows.subList(start, rows.size())); saturationCases++;
                }
            }
            inv.clear(); player.getInventory().clearContent(); menu.setCarried(ItemStack.EMPTY);
        }
        void stale(long id) throws Exception {
            menu.setCarried(pattern(Items.EMERALD));
            Ae2RemoteNativeSmoke.observed(menu, false, rows, () -> action(InventoryAction.PICKUP_OR_SET_DOWN, 0, id), 0, 0);
            check(menu.getCarried().getCount() == 1, "stale ID consumed cursor");
        }
        void action(InventoryAction action, int slot, long id) {
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                new InventoryActionPacket(action, slot, id).write(wire);
                var decoded = InventoryActionPacket.decode(wire);
                check(!wire.isReadable() && decoded.extraId() == id && decoded.action() == action && decoded.slot() == slot, "action wire changed target");
                decoded.handleOnServer(player);
            } finally { wire.release(); }
        }
        void broadcast() { menu.broadcastChanges(); }
        void receive(Packet<?> packet) {
            if (!(packet instanceof ClientboundCustomPayloadPacket custom)) return;
            if (custom.payload() instanceof ClearPatternAccessTerminalPacket) { mirror.clear(); clears++; }
            if (!(custom.payload() instanceof PatternAccessTerminalPacket update)) return;
            var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess());
            try {
                update.write(wire); var decoded = PatternAccessTerminalPacket.decode(wire);
                check(!wire.isReadable(), "trailing pattern payload bytes");
                updates.add(decoded); wirePackets++;
                if (decoded.fullUpdate()) {
                    check(decoded.group() != null && decoded.inventorySize() > 0, "full packet missing group/size");
                    mirror.put(decoded.inventoryId(), new ArrayList<>(Collections.nCopies(decoded.inventorySize(), ItemStack.EMPTY)));
                }
                var slots = Objects.requireNonNull(mirror.get(decoded.inventoryId()), "incremental unknown ID");
                decoded.slots().forEach((slot, stack) -> slots.set(slot, stack.copy()));
            } finally { wire.release(); }
        }
        long id(PatternProviderLogicHost host) throws Exception {
            var field = PatternAccessTermMenu.class.getDeclaredField("byId"); field.setAccessible(true);
            var trackers = (it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<?>)field.get(menu);
            for (Object tracker : trackers.values()) {
                var access = (PatternTrackerAccess)tracker;
                if (access.avilixlogger$container() == host) return access.avilixlogger$serverId();
            }
            throw new AssertionError("native discovery omitted provider");
        }
        void close() {
            if (processing != null) { processing.close(); processing = null; }
            if (assembly != null) { assembly.close(); assembly = null; }
            for (var pos : positions) level.removeBlock(pos, false);
            for (var node : nodes) node.destroy();
            if (!wasForced) level.setChunkForced(2, 2, false);
            mirror.clear(); updates.clear(); rows.clear();
        }
    }
    private static ItemStack pattern(Item output) {
        return PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.STONE), 2)), List.of(new GenericStack(AEItemKey.of(output), 3)));
    }
    private static List<ItemStack> snapshot(InternalInventory inv) { var out = new ArrayList<ItemStack>(); for (int i = 0; i < inv.size(); i++) out.add(inv.getStackInSlot(i).copy()); return out; }
    private static void same(List<ItemStack> actual, List<ItemStack> expected, String why) { check(actual.size() == expected.size(), why); for (int i = 0; i < actual.size(); i++) check(ItemStack.matches(actual.get(i), expected.get(i)), why + ":slot=" + i); }
    private static int deltaRows(List<ItemStack> before, List<ItemStack> after) {
        int count = 0;
        for (int i = 0; i < before.size(); i++) {
            var a = before.get(i); var b = after.get(i);
            if (ItemStack.matches(a, b)) continue;
            count += ItemStack.isSameItemSameComponents(a, b) ? 1 : (a.isEmpty() ? 0 : 1) + (b.isEmpty() ? 0 : 1);
        }
        return count;
    }
    private static void verifyDeltas(ServerLevel level, List<ItemStack> before, List<ItemStack> after, List<LogEntry> rows) {
        for (int slot = 0; slot < before.size(); slot++) {
            var a = before.get(slot); var b = after.get(slot); int index = slot;
            var actual = rows.stream().filter(r -> r.source.endsWith(":slot=" + index)).toList();
            if (ItemStack.matches(a, b)) { check(actual.isEmpty(), "unchanged saturated slot logged"); continue; }
            int expected = ItemStack.isSameItemSameComponents(a, b) ? 1 : (a.isEmpty() ? 0 : 1) + (b.isEmpty() ? 0 : 1);
            check(actual.size() == expected && actual.stream().map(r -> r.type).distinct().count() == expected, "saturated slot lost/duplicated a delta");
            for (var row : actual) {
                check(row.type == ActionType.ME_TAKE || row.type == ActionType.ME_PUT, "unexpected saturated delta type");
                var item = row.type == ActionType.ME_TAKE ? a : b;
                int amount = ItemStack.isSameItemSameComponents(a, b) ? Math.abs(a.getCount() - b.getCount()) : item.getCount();
                check(row.count == amount, "saturated delta amount changed");
                var saved = ItemStack.parseOptional(level.registryAccess(),
                        (net.minecraft.nbt.CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.ITEM));
                check(ItemStack.isSameItemSameComponents(saved, item), "saturated delta lost pattern components");
            }
        }
    }
}
