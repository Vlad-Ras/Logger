package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.parts.*;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.*;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.me.helpers.MachineSource;
import appeng.parts.automation.ImportBusPart;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import java.util.*;
import java.util.concurrent.*;

/** Native external recipe route and autonomous interface requester; all setup/probes are dev-only. */
public final class Ae2ProcessingNativeSmoke implements AutoCloseable {
    private final ServerLevel level;
    private final ServerPlayer player;
    private final PatternProviderLogicHost provider;
    private final CraftingBlockEntity cpuBlock;
    private final IManagedGridNode power;
    private final List<BlockPos> positions = new ArrayList<>();
    private final List<LogEntry> rows = new ArrayList<>();
    private DriveBlockEntity drive;
    private InterfaceBlockEntity requester;
    private AbstractFurnaceBlockEntity furnace;
    private ImportBusPart importer;
    private Future<ICraftingPlan> calculation;
    private Ae2RequesterCancelNativeSmoke cancellation;
    private Ae2CraftingAudit.Completion completion;
    private ICraftingLink cpuLink, requestLink;
    private UUID firstJob;
    private boolean connected;
    private int phase, ticks, stable;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public Ae2ProcessingNativeSmoke(ServerLevel level, ServerPlayer player, PatternProviderLogicHost provider, CraftingBlockEntity cpuBlock, IManagedGridNode power) {
        this.level = level; this.player = player; this.provider = provider; this.cpuBlock = cpuBlock; this.power = power;
    }
    private appeng.me.cluster.implementations.CraftingCPUCluster cpu() { return cpuBlock.getCluster(); }
    private IGrid grid() { return power.getGrid(); }
    private MachineSource source() { return new MachineSource(requester); }
    private long count(Item item) { return grid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, source()); }
    private long stock() { return requester.getInterfaceLogic().getStorage().getAmount(0); }
    private boolean drained() { return furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty() && provider.getLogic().getReturnInv().isEmpty(); }
    private void block(BlockPos pos, net.minecraft.world.level.block.state.BlockState state) { positions.add(pos); level.setBlockAndUpdate(pos, state); }
    public void setup() {
        check(!Ae2Audit.enabled() && !cpu().isBusy(), "processing setup changed authorization or busy CPU");
        // Vanilla furnaces accept ingredients only from the top; horizontal access is fuel-only.
        var furnacePos = provider.getBlockEntity().getBlockPos().below();
        block(furnacePos, Blocks.BLAST_FURNACE.defaultBlockState());
        furnace = (AbstractFurnaceBlockEntity)Objects.requireNonNull(level.getBlockEntity(furnacePos));
        var input = level.getCapability(net.neoforged.neoforge.capabilities.Capabilities.ItemHandler.BLOCK, furnacePos, Direction.UP);
        check(input != null && input.insertItem(0, new ItemStack(Items.RAW_IRON), true).isEmpty() && furnace.getItem(0).isEmpty(), "native top input cannot simulate raw iron without mutation");
        var busPos = furnacePos.below(); block(busPos, AEBlocks.CABLE_BUS.block().defaultBlockState());
        importer = (ImportBusPart)((IPartHost)Objects.requireNonNull(level.getBlockEntity(busPos)))
                .addPart((IPartItem<?>)AEParts.IMPORT_BUS.asItem(), Direction.UP, player);
        check(importer != null, "native import bus not installed below output face");
        // A valid filter that deliberately refuses the recipe output until the test releases it.
        importer.getConfig().setStack(0, new GenericStack(AEItemKey.of(Items.DIAMOND), 0));
        var drivePos = new BlockPos(32, 110, 35); block(drivePos, AEBlocks.DRIVE.block().defaultBlockState());
        drive = (DriveBlockEntity)Objects.requireNonNull(level.getBlockEntity(drivePos));
        drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_1K.stack());
        var requestPos = new BlockPos(32, 110, 37); block(requestPos, AEBlocks.INTERFACE.block().defaultBlockState());
        requester = (InterfaceBlockEntity)Objects.requireNonNull(level.getBlockEntity(requestPos));
        var recipeInput = new SingleRecipeInput(new ItemStack(Items.RAW_IRON));
        var recipe = level.getRecipeManager().getRecipeFor(RecipeType.BLASTING, recipeInput, level).orElseThrow();
        var output = recipe.value().assemble(recipeInput, level.registryAccess());
        check(output.is(Items.IRON_INGOT) && output.getCount() == 1, "unexpected actual blasting recipe");
        var patterns = provider.getTerminalPatternInventory(); patterns.clear();
        patterns.setItemDirect(0, PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 1)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1))));
    }
    private void watch() {
        cpuLink = Objects.requireNonNull(cpu().craftingLogic.getLastLink());
        check(cpu().craftingLogic.getFinalJobOutput().equals(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2)), "native processing job changed planned output");
        completion = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu()));
    }
    private void premature() {
        var scope = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu()));
        check(scope.finish(true) == null && cpu().isBusy(), "incomplete external recipe fabricated CPU finish");
    }
    private void finished() {
        var row = completion.finish(true); check(row != null && completion.finish(true) == null, "processing completion missing/duplicated"); completion = null;
        var tag = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
        check(row.type == ActionType.ME_CRAFT_FINISH && row.actorUuid == null && tag.getUUID("JobId").equals(cpuLink.getCraftingID())
                && row.x == cpuBlock.getBlockPos().getX() && row.y == cpuBlock.getBlockPos().getY() && row.z == cpuBlock.getBlockPos().getZ(), "processing finish lost neutral CPU/job origin");
        check(tag.getLong("Amount") == 2 && tag.getString("QuantityMeaning").equals("planned-output"), "partial recipe delivery replaced original planned amount");
        rows.add(row);
    }
    public boolean tick() throws Exception {
        if (++ticks > 4600) throw new AssertionError("processing timeout phase=" + phase + " busy=" + cpu().isBusy()
                + " raw=" + count(Items.RAW_IRON) + " cellIron=" + count(Items.IRON_INGOT) + " interface=" + stock()
                + " furnace=" + List.of(furnace.getItem(0), furnace.getItem(1), furnace.getItem(2)));
        check(!Ae2Audit.enabled(), "processing fixture enabled production capture");
        if (!connected) {
            if (!drive.getMainNode().isReady() || !requester.getMainNode().isReady() || !importer.getMainNode().isReady()) return false;
            GridHelper.createConnection(drive.getMainNode().getNode(), power.getNode());
            GridHelper.createConnection(requester.getMainNode().getNode(), power.getNode());
            GridHelper.createConnection(importer.getMainNode().getNode(), power.getNode()); connected = true; return false;
        }
        if (!drive.getMainNode().isActive() || !requester.getMainNode().isActive() || !importer.getMainNode().isActive()) return false;
        if (phase == 0) {
            if (!grid().getCraftingService().isCraftable(AEItemKey.of(Items.IRON_INGOT))) return false;
            long n = grid().getStorageService().getInventory().insert(AEItemKey.of(Items.RAW_IRON), 2, Actionable.MODULATE, source());
            check(n == 0 || n == 2, "partial processing seed"); if (n == 0) return false;
            phase = 1; return false;
        }
        if (phase == 1) {
            calculation = grid().getCraftingService().beginCraftingCalculation(level, this::source, AEItemKey.of(Items.IRON_INGOT), 2, CalculationStrategy.REPORT_MISSING_ITEMS);
            phase = 2; return false;
        }
        if (phase == 2) {
            if (!calculation.isDone()) return false;
            var plan = calculation.get(0, TimeUnit.MILLISECONDS); calculation = null;
            check(!plan.simulation() && plan.patternTimes().size() == 1 && plan.usedItems().get(AEItemKey.of(Items.RAW_IRON)) == 2, "processing plan not calculated from native stock/pattern");
            var capture = Objects.requireNonNull(Ae2CraftingAudit.inspectSubmission(plan, source(), cpu()));
            var result = grid().getCraftingService().submitJob(plan, null, null, true, source());
            check(result.successful() && result.link() == null && cpu().isBusy(), "native machine-source processing submit failed");
            var row = Objects.requireNonNull(capture.finish(result, true)); rows.add(row);
            check(row.actorUuid == null && row.x == requester.getBlockPos().getX() && row.y == requester.getBlockPos().getY()
                    && row.z == requester.getBlockPos().getZ(), "machine-source receipt blamed nearby player/lost machine address");
            watch(); firstJob = cpuLink.getCraftingID(); phase = 3; return false;
        }
        if (phase == 3) {
            if (furnace.getItem(0).isEmpty()) return false;
            check(furnace.getItem(0).is(Items.RAW_IRON) && furnace.getItem(1).isEmpty() && furnace.getItem(2).isEmpty()
                    && count(Items.IRON_INGOT) == 0 && stock() == 0 && cpu().isBusy(), "unfueled external recipe produced items/finished");
            if (++stable < 30) return false;
            premature(); furnace.setItem(1, new ItemStack(Items.COAL)); furnace.setChanged(); phase = 4; stable = 0; return false;
        }
        if (phase == 4) {
            if (furnace.getItem(2).isEmpty()) return false;
            check(furnace.getItem(2).is(Items.IRON_INGOT) && count(Items.IRON_INGOT) == 0 && cpu().isBusy(), "blocked import falsely delivered output");
            if (++stable < 10) return false;
            premature(); importer.getConfig().setStack(0, null); phase = 5; return false;
        }
        if (phase == 5) {
            if (count(Items.IRON_INGOT) != 1) return false;
            premature(); check(cpu().craftingLogic.getFinalJobOutput().amount() == 2, "first delivery shrank planned receipt quantity"); phase = 6; return false;
        }
        if (phase == 6) {
            if (cpu().isBusy() || !drained() || count(Items.IRON_INGOT) != 2) return false;
            check(count(Items.RAW_IRON) == 0 && stock() == 0, "standalone external route balance changed"); finished();
            check(grid().getStorageService().getInventory().extract(AEItemKey.of(Items.IRON_INGOT), 2, Actionable.MODULATE, source()) == 2, "dev route reset missing output");
            check(grid().getStorageService().getInventory().insert(AEItemKey.of(Items.RAW_IRON), 2, Actionable.MODULATE, source()) == 2, "autonomous seed missing");
            requester.getInterfaceLogic().getUpgrades().setItemDirect(0, AEItems.CRAFTING_CARD.stack());
            requester.getInterfaceLogic().getConfig().setStack(0, new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2));
            phase = 7; return false;
        }
        if (phase == 7) {
            if (!cpu().isBusy()) return false;
            watch(); check(!cpuLink.isStandalone() && !cpuLink.getCraftingID().equals(firstJob), "autonomous requester did not create a new non-standalone CPU job");
            var requested = requester.getInterfaceLogic().getRequestedJobs(); check(requested.size() == 1, "interface did not own its actual job link");
            requestLink = requested.iterator().next();
            check(requestLink != cpuLink && requestLink.getCraftingID().equals(cpuLink.getCraftingID()) && !requestLink.isStandalone(), "requester/CPU link pair mismatched");
            check(stock() == 0 && count(Items.IRON_INGOT) == 0, "autonomous route prefilled output"); phase = 8; return false;
        }
        if (phase == 8) {
            if (stock() != 1) return false;
            premature(); check(count(Items.IRON_INGOT) == 0 && !requestLink.isDone(), "requester output leaked into cell or completed early"); phase = 9; return false;
        }
        if (phase == 9) {
            if (cpu().isBusy() || !drained() || stock() != 2) return false;
            check(requester.getInterfaceLogic().getStorage().getKey(0).equals(AEItemKey.of(Items.IRON_INGOT)) && count(Items.RAW_IRON) == 0
                    && count(Items.IRON_INGOT) == 0 && requestLink.isDone() && !requestLink.isCanceled()
                    && requester.getInterfaceLogic().getRequestedJobs().isEmpty(), "native requester delivery/terminal callback balance changed");
            finished(); phase = 10; stable = 0; return false;
        }
        if (phase == 10) {
            check(!cpu().isBusy() && stock() == 2 && count(Items.IRON_INGOT) == 0 && requester.getInterfaceLogic().getRequestedJobs().isEmpty()
                    && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "satisfied interface resubmitted or duplicated output");
            if (++stable < 30) return false;
            pipeline(); check(rows.size() == 3, "external recipe receipt count changed");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_PROCESSING_NATIVE_OK: actual blasting recipe, provider -> vanilla blast furnace -> bottom import bus; unfueled/blocked return/partial delivery guards; standalone machine-source 2 raw iron -> 2 cell ingots; autonomous crafting-card interface requests and receives exactly 2 ingots, paired native requester/CPU links done and released; rows=3, finished=2; data-only inspectors, autonomous admission/production authenticated emit not observed");
            cancellation = new Ae2RequesterCancelNativeSmoke(level, player, requester, cpuBlock, furnace);
            cancellation.setup(); phase = 11; return false;
        }
        if (phase == 11) return cancellation.tick();
        throw new AssertionError("unknown processing phase " + phase);
    }
    private void pipeline() {
        var received = new CopyOnWriteArrayList<LogEntry>();
        var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        }, 2, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == 3, "processing worker dropped receipts");
        for (int i = 0; i < received.size(); i++) {
            var row = received.get(i); var tag = Ae2CraftingDetails.context(row);
            check(row.type == rows.get(i).type && row.actorUuid == null && tag != null && tag.getLong("Amount") == 2
                    && tag.getString("Resource").equals("minecraft:iron_ingot") && tag.getString("QuantityMeaning").equals("planned-output") && row.itemStackNbt != null, "external route FIFO/full item/long/neutral actor evidence lost");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(replay.beAfter.equals(row.beAfter) && replay.itemStackNbt.equals(row.itemStackNbt), "external route Gzip lost evidence");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-crafting-v1"), "processing UI leaked prefix");
            var rollback = RollbackEngine.applyPreparedEntry(level, row); check(rollback.applied == 0 && rollback.skipped == 1, "external recipe receipt performed item rollback");
        }
    }
    public void close() {
        if (cancellation != null) cancellation.close(); cancellation = null;
        if (calculation != null) calculation.cancel(true); calculation = null;
        if (completion != null) completion.close(); completion = null;
        if (requester != null) requester.getInterfaceLogic().getConfig().setStack(0, null);
        if (cpu() != null && cpu().isBusy()) cpu().cancelJob();
        for (var pos : positions) level.removeBlock(pos, false);
        rows.clear(); cpuLink = null; requestLink = null;
    }
}
