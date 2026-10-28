package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.*;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.*;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.PlayerSource;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.parts.reporting.PatternAccessTerminalPart;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Actual recipe calculation and assembler ticks. Every game operation here is dev-fixture only. */
public final class Ae2AssemblyNativeSmoke implements AutoCloseable {
    private static final RuntimeException FAULT = new RuntimeException("intentional native CPU finish failure");
    private static final List<Item> ITEMS = List.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK, Items.HONEY_BOTTLE, Items.HONEY_BLOCK, Items.GLASS_BOTTLE);
    private final ServerLevel level;
    private final ServerPlayer player;
    private final PatternAccessTerminalPart terminal;
    private final PatternProviderLogicHost provider;
    private final CraftingBlockEntity cpuBlock;
    private final IManagedGridNode power;
    private final List<BlockPos> positions = new ArrayList<>();
    private final List<LogEntry> rows = new ArrayList<>();
    private final Set<UUID> jobIds = new HashSet<>();
    private MolecularAssemblerBlockEntity assembler;
    private DriveBlockEntity drive;
    private Future<ICraftingPlan> calculation;
    private Ae2CraftingAudit.Completion completion;
    private ICraftingLink currentLink;
    private int phase, ticks, successfulChains;
    private boolean connected;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public Ae2AssemblyNativeSmoke(ServerLevel level, ServerPlayer player, PatternAccessTerminalPart terminal,
                                  PatternProviderLogicHost provider, CraftingBlockEntity cpuBlock, IManagedGridNode power) {
        this.level = level; this.player = player; this.terminal = terminal; this.provider = provider; this.cpuBlock = cpuBlock; this.power = power;
    }
    private IActionSource source() { return new PlayerSource(player, terminal); }
    private appeng.api.networking.IGrid grid() { return terminal.getGridNode().getGrid(); }
    private appeng.me.cluster.implementations.CraftingCPUCluster cpu() { return cpuBlock.getCluster(); }
    private long count(Item item) { return grid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, source()); }
    private long insert(Item item, long amount) { return grid().getStorageService().getInventory().insert(AEItemKey.of(item), amount, Actionable.MODULATE, source()); }
    public void setup() {
        check(!Ae2Audit.enabled() && !cpu().isBusy(), "assembler fixture changed authorization or CPU busy");
        var assemblerPos = provider.getBlockEntity().getBlockPos().south(); positions.add(assemblerPos);
        level.setBlockAndUpdate(assemblerPos, AEBlocks.MOLECULAR_ASSEMBLER.block().defaultBlockState());
        assembler = (MolecularAssemblerBlockEntity)Objects.requireNonNull(level.getBlockEntity(assemblerPos));
        var drivePos = new BlockPos(32, 110, 35); positions.add(drivePos);
        level.setBlockAndUpdate(drivePos, AEBlocks.DRIVE.block().defaultBlockState());
        drive = (DriveBlockEntity)Objects.requireNonNull(level.getBlockEntity(drivePos));
        drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_1K.stack());
        var patterns = provider.getTerminalPatternInventory(); patterns.clear();
        patterns.setItemDirect(0, pattern(Map.of(0, Items.OAK_LOG), Items.OAK_PLANKS, 4));
        patterns.setItemDirect(1, pattern(Map.of(0, Items.OAK_PLANKS, 3, Items.OAK_PLANKS), Items.STICK, 4));
        patterns.setItemDirect(2, pattern(Map.of(0, Items.HONEY_BOTTLE, 1, Items.HONEY_BOTTLE, 3, Items.HONEY_BOTTLE, 4, Items.HONEY_BOTTLE), Items.HONEY_BLOCK, 1));
    }
    private ItemStack pattern(Map<Integer, Item> values, Item expected, int count) {
        var slots = new ItemStack[9]; Arrays.fill(slots, ItemStack.EMPTY); values.forEach((i, item) -> slots[i] = new ItemStack(item));
        var input = CraftingInput.ofPositioned(3, 3, Arrays.asList(slots)).input();
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
        var output = recipe.value().assemble(input, level.registryAccess());
        check(output.is(expected) && output.getCount() == count, "unexpected actual vanilla recipe: " + recipe.id());
        var pattern = PatternDetailsHelper.encodeCraftingPattern(recipe, slots, output, false, false);
        check(PatternDetailsHelper.decodePattern(pattern, level) != null, "native crafting pattern decode failed"); return pattern;
    }
    private void calculate(Item output, int amount) {
        check(calculation == null, "overlapping native recipe calculations");
        calculation = grid().getCraftingService().beginCraftingCalculation(level, this::source, AEItemKey.of(output), amount, CalculationStrategy.REPORT_MISSING_ITEMS);
    }
    private ICraftingPlan calculated() throws Exception {
        if (!calculation.isDone()) return null;
        var plan = calculation.get(0, TimeUnit.MILLISECONDS); calculation = null; return plan;
    }
    private void submit(ICraftingPlan plan) {
        var accepted = Ae2CraftingAudit.inspectSubmission(plan, source(), cpu());
        var refused = Ae2CraftingAudit.inspectSubmission(plan, source(), null);
        var result = grid().getCraftingService().submitJob(plan, null, null, true, source());
        check(result.successful() && result.link() == null && cpu().isBusy(), "native calculated plan not admitted: " + result.errorCode());
        check(refused.finish(result, false) == null, "accepted recipe duplicated at service");
        var row = accepted.finish(result, true); check(row != null, "native calculated admission missing"); rows.add(row);
        currentLink = cpu().craftingLogic.getLastLink(); check(currentLink != null && jobIds.add(currentLink.getCraftingID()), "recipe job reused native identity");
        check(Ae2CraftingAudit.inspectCompletion(cpu()).finish(true) == null, "running job prematurely recorded as finished");
        completion = Ae2CraftingAudit.inspectCompletion(cpu()); check(completion != null, "recipe completion inspector missing");
    }
    private void completed() {
        var row = completion.finish(true); check(row != null && completion.finish(true) == null, "completion missing or duplicated"); completion = null;
        check(row.type == ActionType.ME_CRAFT_FINISH && row.actorUuid == null, "CPU completion blamed current/nearby player");
        var tag = (net.minecraft.nbt.CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
        check(tag.getUUID("JobId").equals(currentLink.getCraftingID()) && row.x == cpuBlock.getBlockPos().getX()
                && row.y == cpuBlock.getBlockPos().getY() && row.z == cpuBlock.getBlockPos().getZ(), "completion lost original job/CPU address");
        rows.add(row); currentLink = null; successfulChains++;
    }
    private boolean drained() { return assembler.getInternalInventory().isEmpty() && provider.getLogic().getReturnInv().isEmpty(); }
    private void clearStorage() {
        check(!cpu().isBusy() && drained(), "reset would hide in-flight native items");
        for (var item : ITEMS) grid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.MODULATE, source());
    }
    public boolean tick() throws Exception {
        if (++ticks > 1000) throw new AssertionError("native assembly timeout phase=" + phase + " busy=" + cpu().isBusy() + " stocks=" + ITEMS.stream().map(i -> i + "=" + count(i)).toList());
        if (!connected) {
            if (!drive.getMainNode().isReady() || !assembler.getMainNode().isReady()) return false;
            GridHelper.createConnection(drive.getMainNode().getNode(), power.getNode()); connected = true;
            return false;
        }
        if (phase == 0) {
            if (!drive.getMainNode().isActive() || !assembler.getMainNode().isActive()) return false;
            check(assembler.getMainNode().getGrid() == grid(), "adjacent assembler did not join provider grid");
            if (!grid().getCraftingService().isCraftable(AEItemKey.of(Items.STICK)) || !grid().getCraftingService().isCraftable(AEItemKey.of(Items.HONEY_BLOCK))) return false;
            calculate(Items.STICK, 8); phase = 1; return false;
        }
        if (phase == 1) {
            var plan = calculated(); if (plan == null) return false;
            check(plan.simulation() && !plan.missingItems().isEmpty() && !cpu().isBusy(), "missing inputs did not produce native simulation");
            var capture = Ae2CraftingAudit.inspectSubmission(plan, source(), null);
            var result = grid().getCraftingService().submitJob(plan, null, null, true, source());
            check(!result.successful() && result.errorCode().name().equals("INCOMPLETE_PLAN"), "simulated recipe was executed");
            rows.add(Objects.requireNonNull(capture.finish(result, false))); phase = 2; return false;
        }
        if (phase == 2) {
            long inserted = insert(Items.OAK_LOG, 2); check(inserted == 0 || inserted == 2, "partial seed insert");
            if (inserted == 0) return false; phase = 3; return false;
        }
        if (phase == 3) { calculate(Items.STICK, 8); phase = 4; return false; }
        if (phase == 4) {
            var plan = calculated(); if (plan == null) return false;
            check(!plan.simulation() && plan.patternTimes().size() == 2 && plan.usedItems().get(AEItemKey.of(Items.OAK_LOG)) == 1
                    && plan.finalOutput().what().equals(AEItemKey.of(Items.STICK)) && plan.finalOutput().amount() == 8, "native log/planks/sticks plan was not a two-recipe chain");
            submit(plan); phase = 5; return false;
        }
        if (phase == 5) {
            if (cpu().isBusy() || !drained() || count(Items.STICK) != 8) return false;
            check(count(Items.OAK_LOG) == 1 && count(Items.OAK_PLANKS) == 0, "native chain input/output balance changed");
            completed(); clearStorage(); check(insert(Items.HONEY_BOTTLE, 4) == 4, "honey seed missing"); phase = 6; return false;
        }
        if (phase == 6) { calculate(Items.HONEY_BLOCK, 1); phase = 7; return false; }
        if (phase == 7) {
            var plan = calculated(); if (plan == null) return false;
            check(!plan.simulation() && plan.patternTimes().size() == 1 && plan.usedItems().get(AEItemKey.of(Items.HONEY_BOTTLE)) == 4, "native remainder recipe plan changed");
            submit(plan); phase = 8; return false;
        }
        if (phase == 8) {
            if (cpu().isBusy() || !drained() || count(Items.HONEY_BLOCK) != 1 || count(Items.GLASS_BOTTLE) != 4) return false;
            check(count(Items.HONEY_BOTTLE) == 0, "native honey inputs not consumed");
            completed(); clearStorage(); check(insert(Items.OAK_LOG, 2) == 2, "cancel seed missing"); phase = 9; return false;
        }
        if (phase == 9) { calculate(Items.STICK, 16); phase = 10; return false; }
        if (phase == 10) {
            var plan = calculated(); if (plan == null) return false;
            check(!plan.simulation() && plan.usedItems().get(AEItemKey.of(Items.OAK_LOG)) == 2, "cancel recipe plan was simulated or wrong");
            submit(plan);
            var previous = player.containerMenu; var menu = new CraftingCPUMenu(CraftingCPUMenu.TYPE, 120, player.getInventory(), cpuBlock); player.containerMenu = menu;
            try {
                var cancel = Ae2CraftingAudit.inspectCancellation(menu, cpu()); menu.receiveClientAction("cancelCrafting", "null");
                var row = Objects.requireNonNull(cancel.finish());
                check(((net.minecraft.nbt.CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)).getUUID("JobId").equals(currentLink.getCraftingID()), "calculated cancellation used wrong job ID");
                rows.add(row); check(completion.finish(true) == null, "cancelled calculated job recorded as success"); completion = null;
            } finally { menu.removed(player); player.containerMenu = previous; }
            check(!cpu().isBusy() && count(Items.OAK_LOG) == 2 && count(Items.STICK) == 0 && count(Items.OAK_PLANKS) == 0 && drained(), "immediate calculated cancellation did not return exact native inputs");
            finishWrapper(); pipeline();
            check(rows.size() == 7 && successfulChains == 2 && jobIds.size() == 3 && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "native recipe event count/idle scope incorrect");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_ASSEMBLY_NATIVE_OK: native asynchronous recipe plans; missing inputs/refusal, log -> planks -> 8 sticks, 4 honey bottles -> honey block + 4 glass bottles, immediate cancel returns 2 logs; actual provider/assembler/drive/cell/CPU ticks; finished=2, rows={}, jobs={}; inspector-only, authenticated production emit not tested", rows.size(), jobIds.size());
            return true;
        }
        throw new AssertionError("unknown assembly phase " + phase);
    }
    private void finishWrapper() throws Exception {
        var logic = cpu().craftingLogic;
        var method = Arrays.stream(logic.getClass().getDeclaredMethods()).filter(m -> m.getName().contains("avilixlogger$finishJob")
                && m.getParameterCount() == 2 && m.getParameterTypes()[1] == Operation.class).findFirst().orElseThrow(); method.setAccessible(true);
        for (boolean success : List.of(false, true)) {
            int[] calls = {0};
            try { method.invoke(logic, success, (Operation<Void>)args -> { calls[0]++; check(args.length == 1 && args[0].equals(success), "finish wrapper changed native flag"); throw FAULT; });
                throw new AssertionError("finish wrapper swallowed original fault");
            } catch (InvocationTargetException failure) { check(failure.getCause() == FAULT && calls[0] == 1, "finish wrapper repeated/replaced original fault"); }
        }
        check(Ae2CraftingAudit.beginCompletion(cpu()) == null, "unauthorized completion capture enabled");
        var offThread = new java.util.concurrent.atomic.AtomicReference<Ae2CraftingAudit.Completion>();
        var cluster = cpu(); var thread = new Thread(() -> offThread.set(Ae2CraftingAudit.inspectCompletion(cluster))); thread.start(); thread.join(5000);
        check(!thread.isAlive() && offThread.get() == null, "off-thread completion inspector read CPU");
    }
    private void pipeline() {
        var received = new CopyOnWriteArrayList<LogEntry>(); var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        }, 2, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == rows.size(), "recipe receipt pipeline dropped rows");
        for (int i = 0; i < rows.size(); i++) {
            var row = received.get(i); var context = Ae2CraftingDetails.context(row);
            check(context != null && row.type == rows.get(i).type && row.source.equals(rows.get(i).source), "recipe FIFO/evidence changed");
            if (row.type == ActionType.ME_CRAFT_FINISH) check(context.getString("QuantityMeaning").equals("planned-output") && row.actorUuid == null, "completion claimed physical delivery/actor");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(replay.beAfter.equals(row.beAfter) && replay.itemStackNbt.equals(row.itemStackNbt), "actual recipe receipt replay lost item/job evidence");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-crafting-v1"), "recipe UI leaked prefix");
            var rollback = RollbackEngine.applyPreparedEntry(level, row); check(rollback.applied == 0 && rollback.skipped == 1, "recipe receipt performed implicit item rollback");
        }
    }
    public void close() {
        if (calculation != null) calculation.cancel(true); calculation = null;
        if (completion != null) completion.close(); completion = null;
        if (cpu() != null && cpu().isBusy()) cpu().cancelJob();
        for (var pos : positions) level.removeBlock(pos, false);
        rows.clear(); jobIds.clear();
        currentLink = null;
    }
}
