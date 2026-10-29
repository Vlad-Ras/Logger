package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.*;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.*;
import appeng.me.helpers.PlayerSource;
import appeng.menu.me.crafting.CraftingCPUMenu;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Three separate server processes, native on-disk world persistence. Dev only; no AuthCore bypass. */
@EventBusSubscriber(modid="avilixlogger")
public final class Ae2LifecycleNativeSmoke {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke");
    private static final BlockPos PROVIDER = new BlockPos(35, 110, 32), ASSEMBLER = PROVIDER.south(),
            CPU = new BlockPos(44, 110, 32), DRIVE = new BlockPos(32, 110, 35), POWER = new BlockPos(41, 110, 32);
    private static final List<Item> ITEMS = List.of(Items.OAK_LOG, Items.OAK_PLANKS, Items.STICK);
    private static Fixture active;
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void start(ServerLevel level) throws Exception {
        check(active == null, "lifecycle fixture already running");
        var fixture = new Fixture(level); fixture.setup(); active = fixture;
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) {
        var fixture = active; if (fixture == null) return;
        boolean stop = false;
        try { stop = fixture.tick(); }
        catch (Throwable failure) { LOG.error("AE2_NATIVE_FAILED: lifecycle phase=" + fixture.mode, failure); stop = true; }
        if (stop) {
            active = null;
            fixture.release();
            // Deliberately retain blocks, forced chunk and active CPU. Normal shutdown saves the actual world.
            fixture.level.getServer().halt(false);
        }
    }
    private static final class Fixture {
        final ServerLevel level;
        final String mode = System.getProperty("avilixlogger.ae2Lifecycle");
        final List<IManagedGridNode> nodes = new ArrayList<>();
        final List<LogEntry> rows = new ArrayList<>();
        final Path evidence;
        ServerPlayer player;
        PatternProviderBlockEntity provider;
        MolecularAssemblerBlockEntity assembler;
        CraftingBlockEntity cpuBlock;
        DriveBlockEntity drive;
        IManagedGridNode power;
        Future<ICraftingPlan> calculation;
        Ae2CraftingAudit.Completion completion;
        ICraftingLink link;
        UUID actorId, savedJob;
        LogEntry savedRequest;
        int phase, ticks, stable;
        boolean connected;
        Fixture(ServerLevel level) { this.level = level; evidence = level.getServer().getWorldPath(LevelResource.ROOT).resolve("avilix-ae2-lifecycle"); }
        void setup() throws Exception {
            LOG.info("AE2_LIFECYCLE_PROCESS: phase={}, pid={}", mode, ProcessHandle.current().pid());
            check(List.of("seed", "resume", "verify").contains(mode), "unknown lifecycle process phase");
            check(!Ae2Audit.enabled(), "lifecycle fixture changed production authorization");
            level.setChunkForced(2, 2, true);
            if (mode.equals("seed")) {
                check(!Files.exists(evidence), "seed must run in a fresh world");
                level.setBlockAndUpdate(PROVIDER, AEBlocks.PATTERN_PROVIDER.block().defaultBlockState());
                level.setBlockAndUpdate(ASSEMBLER, AEBlocks.MOLECULAR_ASSEMBLER.block().defaultBlockState());
                level.setBlockAndUpdate(CPU, AEBlocks.CRAFTING_STORAGE_1K.block().defaultBlockState());
                level.setBlockAndUpdate(DRIVE, AEBlocks.DRIVE.block().defaultBlockState());
                level.setBlockAndUpdate(POWER, AEBlocks.CREATIVE_ENERGY_CELL.block().defaultBlockState());
                actorId = UUID.randomUUID();
            } else {
                var metadata = GzipJson.fromGzippedJsonBytes(Files.readAllBytes(evidence.resolve("identity.gz")), String[].class);
                actorId = UUID.fromString(metadata[0]); savedJob = UUID.fromString(metadata[1]);
                savedRequest = GzipJson.fromGzippedJsonBytes(Files.readAllBytes(evidence.resolve("request.gz")), LogEntry.class);
                check(savedRequest.type == ActionType.ME_CRAFT_REQUEST && savedRequest.actorUuid.equals(actorId)
                        && Ae2CraftingDetails.context(savedRequest).getUUID("JobId").equals(savedJob), "saved admission lost original actor/job");
            }
            // Load the saved blocks, never replace or inject native NBT on resume/verify.
            provider = (PatternProviderBlockEntity)Objects.requireNonNull(level.getBlockEntity(PROVIDER));
            assembler = (MolecularAssemblerBlockEntity)Objects.requireNonNull(level.getBlockEntity(ASSEMBLER));
            cpuBlock = (CraftingBlockEntity)Objects.requireNonNull(level.getBlockEntity(CPU));
            drive = (DriveBlockEntity)Objects.requireNonNull(level.getBlockEntity(DRIVE));
            power = ((AENetworkedBlockEntity)Objects.requireNonNull(level.getBlockEntity(POWER))).getMainNode();
            nodes.add(provider.getMainNode()); nodes.add(cpuBlock.getMainNode()); nodes.add(drive.getMainNode()); nodes.add(power); nodes.add(assembler.getMainNode());
            player = new ServerPlayer(level.getServer(), level, new com.mojang.authlib.GameProfile(actorId, "Ae2LifecycleEditor"), ClientInformation.createDefault());
            player.setPos(32, 110, 32);
            player.connection = new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                    new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND), player,
                    net.minecraft.server.network.CommonListenerCookie.createInitial(player.getGameProfile(), false)) {
                @Override public boolean isAcceptingMessages() { return true; }
                @Override public void send(Packet<?> packet) {}
                @Override public void send(Packet<?> packet, net.minecraft.network.PacketSendListener callback) {}
            };
            if (mode.equals("seed")) {
                drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_1K.stack());
                provider.getTerminalPatternInventory().setItemDirect(0, pattern(Map.of(0, Items.OAK_LOG), Items.OAK_PLANKS, 4));
                provider.getTerminalPatternInventory().setItemDirect(1, pattern(Map.of(0, Items.OAK_PLANKS, 3, Items.OAK_PLANKS), Items.STICK, 4));
            }
        }
        private appeng.me.cluster.implementations.CraftingCPUCluster cpu() { return cpuBlock.getCluster(); }
        private IGrid grid() { return power.getGrid(); }
        private IActionSource source() { return new PlayerSource(player, provider); }
        private long count(Item item) { return grid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, source()); }
        private long stored(Item item) { return cpu().craftingLogic.getStored(AEItemKey.of(item)); }
        private boolean drained() { return assembler.getInternalInventory().isEmpty() && provider.getLogic().getReturnInv().isEmpty(); }
        private long machine(Item item) {
            long count = 0; var inv = assembler.getInternalInventory();
            for (int i = 0; i < inv.size(); i++) { var stack = inv.getStackInSlot(i); if (stack.is(item)) count += stack.getCount(); }
            return count;
        }
        private ItemStack pattern(Map<Integer, Item> values, Item expected, int count) {
            var slots = new ItemStack[9]; Arrays.fill(slots, ItemStack.EMPTY); values.forEach((i, item) -> slots[i] = new ItemStack(item));
            var input = CraftingInput.ofPositioned(3, 3, Arrays.asList(slots)).input();
            var recipe = level.getRecipeManager().getRecipeFor(RecipeType.CRAFTING, input, level).orElseThrow();
            var output = recipe.value().assemble(input, level.registryAccess());
            check(output.is(expected) && output.getCount() == count, "unexpected lifecycle recipe");
            return PatternDetailsHelper.encodeCraftingPattern(recipe, slots, output, false, false);
        }
        private void calculate() {
            calculation = grid().getCraftingService().beginCraftingCalculation(level, this::source, AEItemKey.of(Items.STICK), 16, CalculationStrategy.REPORT_MISSING_ITEMS);
        }
        private boolean admit() throws Exception {
            if (!calculation.isDone()) return false;
            var plan = calculation.get(0, TimeUnit.MILLISECONDS); calculation = null;
            check(!plan.simulation() && plan.patternTimes().size() == 2 && plan.usedItems().get(AEItemKey.of(Items.OAK_LOG)) == 2, "lifecycle plan wrong");
            var capture = Ae2CraftingAudit.inspectSubmission(plan, source(), cpu());
            var result = grid().getCraftingService().submitJob(plan, null, null, true, source());
            check(result.successful() && cpu().isBusy(), "lifecycle plan admission failed: " + result.errorCode());
            rows.add(Objects.requireNonNull(capture.finish(result, true)));
            link = Objects.requireNonNull(cpu().craftingLogic.getLastLink());
            completion = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu())); return true;
        }
        private void action(String name, boolean cancel) {
            var previous = player.containerMenu;
            var menu = new CraftingCPUMenu(CraftingCPUMenu.TYPE, 126, player.getInventory(), cpuBlock); player.containerMenu = menu;
            try {
                var capture = Ae2CraftingAudit.inspectCancellation(menu, cpu());
                menu.receiveClientAction(name, "null");
                var row = capture == null ? null : capture.finish();
                if (cancel) {
                    check(row != null && Ae2CraftingDetails.context(materialized(row)).getUUID("JobId").equals(link.getCraftingID()), "mid-cancel lost original job"); rows.add(row);
                } else check(row == null, "pause/resume/idle action fabricated cancellation");
            } finally { menu.removed(player); player.containerMenu = previous; }
        }
        boolean tick() throws Exception {
            if (++ticks > 1200) throw new AssertionError("lifecycle timeout mode=" + mode + " phase=" + phase + " CPU=" + (cpu() == null ? "none" : cpu().isBusy())
                    + " cell=" + ITEMS.stream().map(i -> i + "=" + count(i)).toList());
            check(!Ae2Audit.enabled(), "lifecycle fixture enabled production capture");
            if (!connected) {
                if (nodes.stream().anyMatch(n -> !n.isReady()) || cpu() == null) return false;
                if (mode.equals("resume")) {
                    link = Objects.requireNonNull(cpu().craftingLogic.getLastLink());
                    check(link.getCraftingID().equals(savedJob) && cpu().isBusy() && cpu().craftingLogic.isJobSuspended(), "restart lost native job identity/pause");
                    check(machine(Items.OAK_LOG) == 1 && stored(Items.OAK_LOG) == 1 && stored(Items.OAK_PLANKS) == 0, "restart lost in-flight assembler input/CPU balance");
                    check(cpu().craftingLogic.getFinalJobOutput().equals(new GenericStack(AEItemKey.of(Items.STICK), 16)), "restart changed original planned output");
                    completion = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu()));
                    rows.add(savedRequest);
                }
                if (mode.equals("verify")) check(!cpu().isBusy() && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "finished job resurrected after second restart");
                // Dev fixture links are recreated, not persisted game cables. All saved inventories/job state remain native.
                for (int i : List.of(0, 1, 2)) GridHelper.createConnection(nodes.get(i).getNode(), power.getNode());
                connected = true; return false;
            }
            if (nodes.stream().anyMatch(n -> !n.isActive()) || !grid().getCraftingService().getCpus().contains(cpu())) return false;
            if (mode.equals("verify")) {
                check(!cpu().isBusy() && drained() && count(Items.STICK) == 16 && count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 0, "completed world balance changed after restart");
                check(Ae2CraftingAudit.inspectCompletion(cpu()) == null, "idle restored CPU generated finish scope");
                if (++stable < 25) return false;
                var finish = GzipJson.fromGzippedJsonBytes(Files.readAllBytes(evidence.resolve("finish.gz")), LogEntry.class);
                check(finish.type == ActionType.ME_CRAFT_FINISH && finish.actorUuid == null && Ae2CraftingDetails.context(finish).getUUID("JobId").equals(savedJob), "persisted finish lost CPU/job/system actor");
                LOG.info("AE2_LIFECYCLE_NATIVE_OK: three server processes; mid-flight cancel 1 log + 4 planks, suspended in-flight assembler/job restored with same UUID, resumed to exactly 16 sticks, second restart idle/no replay; data-only inspector receipts, production authentication not tested");
                return true;
            }
            if (mode.equals("resume")) return resumed();
            return seed();
        }
        private boolean seed() throws Exception {
            if (phase == 0) {
                if (!grid().getCraftingService().isCraftable(AEItemKey.of(Items.STICK))) return false;
                long n = grid().getStorageService().getInventory().insert(AEItemKey.of(Items.OAK_LOG), 2, Actionable.MODULATE, source());
                check(n == 0 || n == 2, "partial lifecycle seed"); if (n == 0) return false;
                phase = 1; return false;
            }
            if (phase == 1) { calculate(); phase = 2; return false; }
            if (phase == 2) { if (!admit()) return false; phase = 3; return false; }
            if (phase == 3) {
                if (assembler.getInternalInventory().isEmpty()) return false;
                check(cpu().isBusy() && machine(Items.OAK_LOG) == 1 && stored(Items.OAK_LOG) == 1, "cancel did not reach first real machine operation");
                action("cancelCrafting", true);
                check(!cpu().isBusy() && link.isCanceled() && completion.finish(true) == null, "mid-flight cancel fabricated success or retained job"); completion = null;
                action("cancelCrafting", false); phase = 4; return false;
            }
            if (phase == 4) {
                if (!drained() || count(Items.OAK_LOG) != 1 || count(Items.OAK_PLANKS) != 4) return false;
                check(count(Items.STICK) == 0 && !cpu().isBusy(), "cancel scheduled further recipes");
                if (++stable < 25) return false;
                check(rows.size() == 2 && rows.get(0).type == ActionType.ME_CRAFT_REQUEST && rows.get(1).type == ActionType.ME_CRAFT_CANCEL, "mid-cancel missing/duplicate receipt");
                LOG.info("AE2_MID_CANCEL_NATIVE_OK: cancelled after native assembler accepted 1 of 2 logs; unfinished operation drains to 4 planks, other log returned, zero sticks, no false finish or repeated cancel; rows=2");
                for (var item : ITEMS) grid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.MODULATE, source());
                check(grid().getStorageService().getInventory().insert(AEItemKey.of(Items.OAK_LOG), 2, Actionable.MODULATE, source()) == 2, "restart seed not inserted");
                phase = 5; return false;
            }
            if (phase == 5) { calculate(); phase = 6; return false; }
            if (phase == 6) { if (!admit()) return false; phase = 7; return false; }
            if (phase == 7) {
                if (assembler.getInternalInventory().isEmpty()) return false;
                check(machine(Items.OAK_LOG) == 1 && stored(Items.OAK_LOG) == 1, "save point not in actual first assembler operation");
                action("toggleScheduling", false);
                check(cpu().isBusy() && cpu().craftingLogic.isJobSuspended() && completion.finish(true) == null, "pause cleared or completed job"); completion = null;
                check(rows.size() == 3, "pause invented submission/cancel receipt");
                var request = materialized(rows.getLast()); savedJob = link.getCraftingID();
                check(!savedJob.equals(Ae2CraftingDetails.context(materialized(rows.getFirst())).getUUID("JobId")), "second job reused cancelled identity");
                Files.createDirectories(evidence);
                Files.write(evidence.resolve("identity.gz"), GzipJson.toGzippedJsonBytes(new String[]{actorId.toString(), savedJob.toString()}));
                Files.write(evidence.resolve("request.gz"), GzipJson.toGzippedJsonBytes(request));
                LOG.info("AE2_LIFECYCLE_SEEDED: suspended native job={}, CPU log=1, assembler log=1, output=16 sticks; awaiting normal world save", savedJob);
                return true;
            }
            throw new AssertionError("unknown lifecycle seed phase " + phase);
        }
        private boolean resumed() throws Exception {
            check(cpu().craftingLogic.getLastLink() == link || !cpu().isBusy(), "resume silently replaced native job link");
            if (phase == 0) {
                check(cpu().isBusy() && cpu().craftingLogic.isJobSuspended() && count(Items.STICK) == 0 && count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 0, "paused restored job scheduled more work");
                if (!drained() || stored(Items.OAK_LOG) != 1 || stored(Items.OAK_PLANKS) != 4) return false;
                if (++stable < 25) return false;
                action("toggleScheduling", false); check(!cpu().craftingLogic.isJobSuspended(), "native resume did not unpause");
                phase = 1; return false;
            }
            if (phase == 1) {
                if (cpu().isBusy() || !drained() || count(Items.STICK) != 16) return false;
                check(count(Items.OAK_LOG) == 0 && count(Items.OAK_PLANKS) == 0, "resumed final balance lost/duplicated inputs");
                var finish = completion.finish(true); check(finish != null && completion.finish(true) == null, "restored finish lost/duplicated receipt"); completion = null;
                finish = materialized(finish); rows.add(finish);
                var detail = Ae2CraftingDetails.context(finish);
                check(rows.size() == 2 && detail.getUUID("JobId").equals(savedJob) && finish.actorUuid == null
                        && finish.x == CPU.getX() && finish.y == CPU.getY() && finish.z == CPU.getZ(), "restored completion changed original job/CPU/actor");
                check(detail.getString("QuantityMeaning").equals("planned-output") && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "restored finish claimed item delivery or idle scope");
                check(RollbackEngine.applyPreparedEntry(level, finish).applied == 0, "restored job receipt reissued items");
                Files.write(evidence.resolve("finish.gz"), GzipJson.toGzippedJsonBytes(finish));
                LOG.info("AE2_LIFECYCLE_RESUMED: same job={} finished after real restart, exactly 16 sticks, saved request + finish rows=2; no resubmission", savedJob);
                return true;
            }
            throw new AssertionError("unknown lifecycle resume phase " + phase);
        }
        private LogEntry materialized(LogEntry row) {
            var received = new CopyOnWriteArrayList<LogEntry>();
            var storage = new AsyncLogStorage(new LogStorage() {
                public void append(LogEntry entry) { received.add(entry); }
                public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
                public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
                public void shutdown() {}
            }, 2, 1024 * 1024);
            try { storage.append(row); } finally { storage.shutdown(); }
            check(received.size() == 1 && Ae2CraftingDetails.context(received.getFirst()) != null, "lifecycle worker dropped evidence");
            var copy = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(received.getFirst()), LogEntry.class);
            check(Objects.equals(copy.beAfter, received.getFirst().beAfter) && Objects.equals(copy.itemStackNbt, received.getFirst().itemStackNbt), "lifecycle Gzip lost evidence");
            return copy;
        }
        void release() {
            if (calculation != null) calculation.cancel(true); calculation = null;
            if (completion != null) completion.close(); completion = null;
            rows.clear(); link = null;
            // No CPU cancellation, node destruction, block removal or synthetic NBT round trip here.
        }
    }
}
