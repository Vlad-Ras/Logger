package com.roften.avilixlogger.smoke;

import appeng.api.config.Settings;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.parts.IPartHost;
import appeng.api.stacks.*;
import appeng.core.definitions.*;
import appeng.core.network.serverbound.ConfigButtonPacket;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.menu.AEBaseMenu;
import appeng.menu.implementations.*;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.*;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Native pattern logic/device slots; detached evidence only, production AuthCore remains closed. */
public final class Ae2PatternNativeSmoke {
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void run(ServerLevel level, ServerPlayer player) throws Exception {
        check(!Ae2Audit.enabled(), "pattern fixture bypassed authorization");
        var originalMenu = player.containerMenu; var originalConnection = player.connection;
        var positions = new ArrayList<BlockPos>(); var rows = new ArrayList<LogEntry>();
        player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(), new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
            @Override public boolean isAcceptingMessages() { return true; }
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet) {}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet, net.minecraft.network.PacketSendListener callback) {}
        };
        try {
            var terminalPos = position(positions, 0);
            var terminal = (PatternEncodingTerminalPart)part(level, player, terminalPos, AEParts.PATTERN_ENCODING_TERMINAL.asItem());
            var menu = new PatternEncodingTermMenu(91, player.getInventory(), terminal); player.containerMenu = menu;
            var logic = terminal.getLogic();
            action(rows, menu, () -> menu.receiveClientAction("setSubstitution", "true"), 1);
            action(rows, menu, () -> menu.receiveClientAction("setFluidSubstitution", "false"), 1);
            check(Ae2Audit.snapshot(menu).settings().get("avilixlogger:pattern_substitution").equals("true")
                    && Ae2Audit.snapshot(menu).settings().get("avilixlogger:pattern_fluid_substitution").equals("false"), "pattern settings used stale menu fields");
            action(rows, menu, () -> menu.receiveClientAction("setSubstitution", "true"), 0);
            changeMode(rows, menu, EncodingMode.PROCESSING);
            filter(rows, menu, menu.getProcessingInputSlots()[0].index, new ItemStack(Items.STONE, 2));
            filter(rows, menu, menu.getProcessingOutputSlots()[0].index, new ItemStack(Items.OAK_PLANKS, 4));
            filter(rows, menu, menu.getProcessingOutputSlots()[1].index, new ItemStack(Items.STICK, 2));
            action(rows, menu, () -> menu.receiveClientAction("cycleProcessingOutput", "null"), 2);
            click(rows, menu, player, find(menu, "BLANK_PATTERN"), AEItems.BLANK_PATTERN.stack(4), 1);
            action(rows, menu, () -> menu.receiveClientAction("encode", "null"), 2);
            ItemStack processing = menu.slots.get(find(menu, "ENCODED_PATTERN")).getItem().copy();
            check(PatternDetailsHelper.decodePattern(processing, level) != null, "native processing pattern not encoded");
            action(rows, menu, () -> menu.receiveClientAction("encode", "null"), 0);
            click(rows, menu, player, find(menu, "ENCODED_PATTERN"), ItemStack.EMPTY, 1);
            action(rows, menu, () -> menu.receiveClientAction("clear", "null"), 3);
            changeMode(rows, menu, EncodingMode.CRAFTING);
            filter(rows, menu, menu.getCraftingGridSlots()[0].index, new ItemStack(Items.OAK_LOG));
            // Loading the encoded crafting pattern also persists its computed output in the shared config inventory.
            action(rows, menu, () -> menu.receiveClientAction("encode", "null"), 3);
            ItemStack crafting = menu.slots.get(find(menu, "ENCODED_PATTERN")).getItem().copy();
            check(PatternDetailsHelper.decodePattern(crafting, level) != null, "native crafting recipe not encoded");
            click(rows, menu, player, find(menu, "ENCODED_PATTERN"), ItemStack.EMPTY, 1);
            action(rows, menu, () -> menu.receiveClientAction("clear", "null"), 2);
            changeMode(rows, menu, EncodingMode.SMITHING_TABLE);
            filter(rows, menu, menu.getSmithingTableTemplateSlot().index, new ItemStack(Items.NETHERITE_UPGRADE_SMITHING_TEMPLATE));
            filter(rows, menu, menu.getSmithingTableBaseSlot().index, new ItemStack(Items.DIAMOND_SWORD));
            filter(rows, menu, menu.getSmithingTableAdditionSlot().index, new ItemStack(Items.NETHERITE_INGOT));
            action(rows, menu, () -> menu.receiveClientAction("encode", "null"), 2);
            check(PatternDetailsHelper.decodePattern(menu.slots.get(find(menu, "ENCODED_PATTERN")).getItem(), level) != null, "native smithing pattern not encoded");
            click(rows, menu, player, find(menu, "ENCODED_PATTERN"), ItemStack.EMPTY, 1);
            action(rows, menu, () -> menu.receiveClientAction("clear", "null"), 3);
            changeMode(rows, menu, EncodingMode.STONECUTTING);
            filter(rows, menu, find(menu, "STONECUTTING_INPUT"), new ItemStack(Items.STONE));
            var recipes = level.getRecipeManager().getRecipesFor(RecipeType.STONECUTTING, new SingleRecipeInput(new ItemStack(Items.STONE)), level);
            check(!recipes.isEmpty(), "vanilla stonecutting recipe missing"); var recipe = recipes.getFirst().id();
            action(rows, menu, () -> menu.receiveClientAction("setStonecuttingRecipeId", new com.google.gson.Gson().toJson(recipe)), 1);
            menu.broadcastChanges();
            action(rows, menu, () -> menu.receiveClientAction("encode", "null"), 2);
            check(PatternDetailsHelper.decodePattern(menu.slots.get(find(menu, "ENCODED_PATTERN")).getItem(), level) != null, "native stonecutting pattern not encoded");
            action(rows, menu, () -> menu.receiveClientAction("setStonecuttingRecipeId", "null"), 1);
            var settings = Ae2Audit.snapshot(menu).settings(); check(!settings.containsKey("avilixlogger:stonecutting_recipe"), "absent recipe persisted as empty string");
            for (int i = 0; i < 2; i++) {
                var pos = position(positions, 1 + i); PatternProviderLogicHost provider;
                if (i == 0) { level.setBlockAndUpdate(pos, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState()); provider = (PatternProviderLogicHost)level.getBlockEntity(pos); }
                else provider = (PatternProviderLogicHost)part(level, player, pos, AEParts.PATTERN_PROVIDER.asItem());
                var providerMenu = new PatternProviderMenu(PatternProviderMenu.TYPE, 92 + i, player.getInventory(), provider); player.containerMenu = providerMenu;
                int slot = find(providerMenu, "ENCODED_PATTERN");
                click(rows, providerMenu, player, slot, processing.copy(), 1);
                click(rows, providerMenu, player, slot, crafting.copy(), 2);
                click(rows, providerMenu, player, slot, ItemStack.EMPTY, 1);
                action(rows, providerMenu, () -> new ConfigButtonPacket(Settings.BLOCKING_MODE, true).handleOnServer(player), 1);
                provider.getLogic().getReturnInv().setStack(0, new GenericStack(AEItemKey.of(Items.DIAMOND), 7));
                click(rows, providerMenu, player, find(providerMenu, "STORAGE"), ItemStack.EMPTY, 1);
            }
            var inscriberPos = position(positions, 3); level.setBlockAndUpdate(inscriberPos, AEBlocks.INSCRIBER.block().defaultBlockState());
            var inscriber = new InscriberMenu(94, player.getInventory(), (appeng.blockentity.misc.InscriberBlockEntity)level.getBlockEntity(inscriberPos)); player.containerMenu = inscriber;
            click(rows, inscriber, player, find(inscriber, "INSCRIBER_PLATE_TOP"), AEItems.CALCULATION_PROCESSOR_PRESS.stack(), 1);
            click(rows, inscriber, player, find(inscriber, "INSCRIBER_PLATE_TOP"), ItemStack.EMPTY, 1);
            click(rows, inscriber, player, find(inscriber, "INSCRIBER_PLATE_BOTTOM"), AEItems.SILICON_PRESS.stack(), 1);
            click(rows, inscriber, player, find(inscriber, "INSCRIBER_PLATE_BOTTOM"), ItemStack.EMPTY, 1);
            action(rows, inscriber, () -> new ConfigButtonPacket(Settings.AUTO_EXPORT, true).handleOnServer(player), 1);
            pipeline(level, rows);
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_PATTERN_NATIVE_OK: four native encoding modes/recipes, substitution/recipe settings, canonical alias deltas, provider block+part slots/return buffer/settings, both inscriber plates; rows={}; detached worker/replay, authenticated emit not tested", rows.size());
        } finally { player.containerMenu = originalMenu; player.connection = originalConnection; for (var pos : positions) level.removeBlock(pos, false); }
    }
    private static void changeMode(List<LogEntry> rows, PatternEncodingTermMenu menu, EncodingMode mode) {
        action(rows, menu, () -> menu.receiveClientAction("setMode", "\"" + mode + "\""), 1); menu.broadcastChanges();
    }
    private static void filter(List<LogEntry> rows, AEBaseMenu menu, int slot, ItemStack stack) { action(rows, menu, () -> menu.setFilter(slot, stack), 1); }
    private static void click(List<LogEntry> rows, AEBaseMenu menu, ServerPlayer player, int slot, ItemStack stack, int count) {
        menu.setCarried(stack); action(rows, menu, () -> menu.clicked(slot, 0, ClickType.PICKUP, player), count); menu.setCarried(ItemStack.EMPTY);
    }
    private static void action(List<LogEntry> rows, AEBaseMenu menu, Runnable mutation, int count) {
        var before = Ae2Audit.snapshot(menu); check(before != null, "before snapshot failed"); mutation.run(); var after = Ae2Audit.followUpSnapshot(menu, before); check(after != null, "after snapshot failed");
        var changes = Ae2Audit.changes(menu, before, after);
        check(changes.size() == count, menu.getClass().getSimpleName() + " expected " + count + " rows, got " + changes.size() + ": " + changes.stream().map(r -> r.type + "/" + r.extra).toList());
        var target = menu.getTarget(); var be = target instanceof net.minecraft.world.level.block.entity.BlockEntity block ? block : ((appeng.parts.AEBasePart)target).getBlockEntity();
        for (var row : changes) check(row.actorUuid.equals(menu.getPlayer().getUUID()) && row.x == be.getBlockPos().getX() && row.y == be.getBlockPos().getY() && row.z == be.getBlockPos().getZ(), "pattern actor/target lost");
        rows.addAll(changes);
    }
    private static int find(AEBaseMenu menu, String semantic) {
        for (int i = 0; i < menu.slots.size(); i++) if (menu.getSlotSemantic(menu.slots.get(i)) != null && menu.getSlotSemantic(menu.slots.get(i)).id().equals(semantic)) return i;
        throw new AssertionError("Missing native slot " + semantic);
    }
    private static BlockPos position(List<BlockPos> positions, int index) { var pos = new BlockPos(140 + index * 4, 100, 140); positions.add(pos); return pos; }
    private static Object part(ServerLevel level, ServerPlayer player, BlockPos pos, Item item) {
        level.setBlockAndUpdate(pos, AEBlocks.CABLE_BUS.block().defaultBlockState());
        var host = (IPartHost)level.getBlockEntity(pos); var added = host.addPart((appeng.api.parts.IPartItem<?>)item, Direction.NORTH, player);
        check(added != null, "native pattern part not installed"); return added;
    }
    private static void pipeline(ServerLevel level, List<LogEntry> rows) {
        var received = new CopyOnWriteArrayList<LogEntry>(); var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery query) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery query) { return query(query); }
            public void shutdown() {}
        }, 8, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(rows.size() == received.size(), "pattern pipeline lost/duplicated rows");
        for (int i = 0; i < rows.size(); i++) {
            var row = received.get(i); check(row.type == rows.get(i).type && row.source.equals(rows.get(i).source), "pattern FIFO/source changed");
            if (Ae2ConfigurationDetails.configuration(row)) check(Ae2ConfigurationDetails.context(row) != null, "pattern configuration pair lost");
            else check(Ae2TransferDetails.context(row) != null && Ae2TransferDetails.context(row).getString("Domain").equals("device-slot") && row.itemStackNbt != null, "device payload lost");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-"), "pattern UI leaked evidence prefix");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(Objects.equals(replay.beBefore, row.beBefore) && Objects.equals(replay.beAfter, row.beAfter) && Objects.equals(replay.itemStackNbt, row.itemStackNbt), "pattern replay changed complete evidence");
        }
    }
}
