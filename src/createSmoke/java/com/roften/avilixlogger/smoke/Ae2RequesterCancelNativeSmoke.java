package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.blockentity.misc.InterfaceBlockEntity;
import appeng.core.definitions.AEItems;
import appeng.me.helpers.MachineSource;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.compat.ae2.mixin.CraftingLinkAccess;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import appeng.menu.me.crafting.CraftingCPUMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Three real autonomous jobs: card removal, requester API and nexus cancellation after dispatch. */
public final class Ae2RequesterCancelNativeSmoke implements AutoCloseable {
    private static final RuntimeException FAULT = new RuntimeException("intentional linked cancellation fault after native transition");
    private final ServerLevel level;
    private final ServerPlayer player;
    private final InterfaceBlockEntity requester;
    private final CraftingBlockEntity block;
    private final AbstractFurnaceBlockEntity furnace;
    private final List<LogEntry> rows = new ArrayList<>();
    private final Set<UUID> ids = new HashSet<>();
    private ICraftingLink requestLink, cpuLink;
    private Ae2LinkedCancellationState nexus;
    private Ae2CraftingAudit.Completion completion;
    private int mode, phase, ticks, stable;
    public Ae2RequesterCancelNativeSmoke(ServerLevel level, ServerPlayer player, InterfaceBlockEntity requester, CraftingBlockEntity block, AbstractFurnaceBlockEntity furnace) {
        this.level = level; this.player = player; this.requester = requester; this.block = block; this.furnace = furnace;
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private appeng.me.cluster.implementations.CraftingCPUCluster cpu() { return block.getCluster(); }
    private long count(Item item) { return requester.getMainNode().getGrid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, new MachineSource(requester)); }
    private long stock() { return requester.getInterfaceLogic().getStorage().getAmount(0); }
    public void setup() {
        check(!Ae2Audit.enabled() && !cpu().isBusy() && furnace.getItem(0).isEmpty() && furnace.getItem(2).isEmpty(), "requester cancellation setup not idle");
        requester.getInterfaceLogic().getConfig().setStack(0, null);
        requester.getInterfaceLogic().getStorage().setStack(0, null); // Dev reset removes the previous scenario's known output.
        check(count(Items.RAW_IRON) == 0 && count(Items.IRON_INGOT) == 0, "requester reset would hide network items");
        check(requester.getMainNode().getGrid().getStorageService().getInventory().insert(AEItemKey.of(Items.RAW_IRON), 2, Actionable.MODULATE, new MachineSource(requester)) == 2, "requester cancel seed missing");
        requester.getInterfaceLogic().getUpgrades().setItemDirect(0, AEItems.CRAFTING_CARD.stack());
        if (furnace.getItem(1).isEmpty()) furnace.setItem(1, new ItemStack(Items.COAL));
        requester.getInterfaceLogic().getConfig().setStack(0, new GenericStack(AEItemKey.of(Items.IRON_INGOT), 2));
    }
    private static Ae2LinkedCancellationState nexus(ICraftingLink cpuLink) throws Exception {
        var field = cpuLink.getClass().getDeclaredField("tie"); field.setAccessible(true);
        return (Ae2LinkedCancellationState)Objects.requireNonNull(field.get(cpuLink));
    }
    private static Method wrapper(Object nexus) {
        var m = Arrays.stream(nexus.getClass().getDeclaredMethods()).filter(x -> x.getName().contains("avilixlogger$linkedCancel") && x.getParameterCount() == 1
                && x.getParameterTypes()[0] == Operation.class).findFirst().orElseThrow(); m.setAccessible(true); return m;
    }
    private static void invokeNativeCancel(Object nexus) throws Exception {
        var m = nexus.getClass().getDeclaredMethod("cancel"); m.setAccessible(true); m.invoke(nexus);
    }
    private void fault(boolean nativeCancel) throws Exception {
        int[] calls = {0};
        try { wrapper(nexus).invoke(nexus, (Operation<Void>)args -> {
            check(args.length == 0, "nexus wrapper changed original arguments"); calls[0]++;
            if (nativeCancel) { try { invokeNativeCancel(nexus); } catch (Exception failure) { throw new AssertionError(failure); } }
            throw FAULT;
        }); throw new AssertionError("nexus wrapper swallowed original fault"); }
        catch (InvocationTargetException failure) { check(failure.getCause() == FAULT && calls[0] == 1, "nexus wrapper replaced/repeated original fault"); }
    }
    public boolean tick() throws Exception {
        check(++ticks <= 1800, "requester cancellation timeout mode=" + mode + " phase=" + phase + " cell=" + count(Items.IRON_INGOT) + " stock=" + stock());
        check(!Ae2Audit.enabled(), "requester fixture enabled production capture");
        if (phase == 0) {
            if (!cpu().isBusy()) return false;
            cpuLink = Objects.requireNonNull(cpu().craftingLogic.getLastLink());
            var links = requester.getInterfaceLogic().getRequestedJobs(); check(links.size() == 1, "autonomous cancel job has no requester link"); requestLink = links.iterator().next();
            check(ids.add(cpuLink.getCraftingID()) && requestLink != cpuLink && requestLink.getCraftingID().equals(cpuLink.getCraftingID())
                    && !cpuLink.isStandalone(), "cancel fixture reused a job or wrong link pair");
            nexus = nexus(cpuLink); check(nexus.avilixlogger$cpuLink() == cpuLink && nexus.avilixlogger$requestLink() == requestLink, "nexus view changed native pair");
            var early = Objects.requireNonNull(Ae2CraftingAudit.inspectLinkedCancellation(nexus)); check(early.finish(true) == null, "running nexus fabricated cancel");
            completion = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu()));
            if (mode == 0) { fault(false); check(!nexus.avilixlogger$cancelled() && cpu().isBusy(), "fault-only wrapper changed native state"); offThread(); }
            phase = 1; return false;
        }
        if (phase == 1) {
            if (furnace.getItem(0).getCount() != 2) return false;
            check(furnace.getItem(0).is(Items.RAW_IRON) && furnace.getItem(2).isEmpty() && stock() == 0, "cancel point was not dispatched raw inputs");
            var capture = mode == 3 ? null : Objects.requireNonNull(Ae2CraftingAudit.inspectLinkedCancellation(nexus));
            LogEntry row;
            if (mode == 0) requester.getInterfaceLogic().getUpgrades().setItemDirect(0, ItemStack.EMPTY);
            else if (mode == 1) requestLink.cancel();
            else if (mode == 2) fault(true);
            if (mode == 3) {
                var previous = player.containerMenu; var menu = new CraftingCPUMenu(CraftingCPUMenu.TYPE, 128, player.getInventory(), block); player.containerMenu = menu;
                try { var ui = Objects.requireNonNull(Ae2CraftingAudit.inspectCancellation(menu, cpu())); menu.receiveClientAction("cancelCrafting", "null"); row = Objects.requireNonNull(ui.finish()); check(ui.finish() == null, "paired UI cancellation repeated receipt"); }
                finally { menu.removed(player); player.containerMenu = previous; }
            } else { row = Objects.requireNonNull(capture.finish(mode != 2)); check(capture.finish(true) == null, "linked cancellation repeated receipt"); }
            check(completion.finish(true) == null, "linked cancellation reported success"); completion = null;
            var tag = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
            var address = mode == 3 ? block.getBlockPos() : requester.getBlockPos();
            check(row.type == ActionType.ME_CRAFT_CANCEL && (mode == 3 ? player.getUUID().equals(row.actorUuid) : row.actorUuid == null) && tag.getUUID("JobId").equals(cpuLink.getCraftingID())
                    && tag.getLong("Amount") == 2 && (mode == 3 || tag.getString("CancellationOrigin").equals("linked-job")) && tag.getBoolean("NativeThrew") == (mode == 2)
                    && row.x == address.getX() && row.y == address.getY() && row.z == address.getZ(), "linked cancel lost machine/job/planned quantity/failure evidence");
            rows.add(row);
            check(requestLink.isCanceled() && cpuLink.isCanceled() && !requestLink.isDone() && requester.getInterfaceLogic().getRequestedJobs().isEmpty(), "native cancellation did not propagate/release request link");
            check(Ae2CraftingAudit.inspectLinkedCancellation(nexus) == null && ((CraftingLinkAccess)cpuLink).avilixlogger$rawCancelled(), "cancelled nexus retained eligible capture");
            requestLink.cancel(); check(Ae2CraftingAudit.inspectLinkedCancellation(nexus) == null, "repeat native cancellation fabricated receipt");
            requester.getInterfaceLogic().getUpgrades().setItemDirect(0, ItemStack.EMPTY); // Prevent a replacement craft job; ordinary restocking remains enabled.
            phase = 2; stable = 0; return false;
        }
        if (phase == 2) {
            if (cpu().isBusy() || !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty() || stock() != 2) return false;
            check(count(Items.RAW_IRON) == 0 && count(Items.IRON_INGOT) == 0 && requester.getInterfaceLogic().getRequestedJobs().isEmpty()
                    && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "cancelled job resurrected or changed delivered balance");
            if (++stable < 25) return false;
            if (++mode < 4) { setup(); phase = 0; return false; }
            pipeline(); check(rows.size() == 4 && ids.size() == 4, "linked cancel case count changed");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_REQUESTER_CANCEL_NATIVE_OK: four autonomous real interface jobs; crafting-card removal, requester link API, nexus cancel + original fault once, paired UI cancellation; two dispatched raw iron continue to two stock ingots after cancellation via ordinary restock, no false CPU finish; machineRows=3, uiRows=1, rows=4; inspector-only, authenticated production emit/dedup not tested");
            return true;
        }
        throw new AssertionError("unknown requester cancel phase");
    }
    private void offThread() throws Exception {
        var poison = new Ae2LinkedCancellationState() {
            private AssertionError read() { return new AssertionError("off-thread/disabled nexus state read"); }
            public ICraftingLink avilixlogger$requestLink() { throw read(); }
            public ICraftingLink avilixlogger$cpuLink() { throw read(); }
            public boolean avilixlogger$cancelled() { throw read(); }
            public boolean avilixlogger$done() { throw read(); }
        };
        check(Ae2CraftingAudit.beginLinkedCancellation(poison) == null, "disabled production capture touched nexus");
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var thread = new Thread(() -> { try { check(Ae2CraftingAudit.inspectLinkedCancellation(poison) == null, "off-thread nexus capture accepted"); } catch (Throwable failure) { error.set(failure); } });
        thread.start(); thread.join(5000); check(!thread.isAlive() && error.get() == null, "off-thread guard failed: " + error.get());
    }
    private void pipeline() {
        var received = new CopyOnWriteArrayList<LogEntry>(); var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        }, 2, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == 4, "linked cancel worker dropped rows");
        var actualIds = new HashSet<UUID>();
        for (int i = 0; i < received.size(); i++) {
            var row = received.get(i); var detail = Ae2CraftingDetails.context(row);
            check(detail != null && row.type == ActionType.ME_CRAFT_CANCEL && detail.getLong("Amount") == 2 && actualIds.add(detail.getUUID("JobId"))
                    && detail.getBoolean("NativeThrew") == (i == 2) && row.itemStackNbt != null, "linked cancel worker lost ordering/full item/identity");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(row.beAfter.equals(replay.beAfter) && row.itemStackNbt.equals(replay.itemStackNbt), "linked cancel Gzip lost evidence");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-crafting-v1"), "linked cancel UI leaked prefix");
            if (i == 2) check(Ae2CraftingDetails.describe(row).stream().anyMatch(c -> c.getString().contains("Отмена подтверждена")), "cancellation fault described as job creation");
            var rollback = RollbackEngine.applyPreparedEntry(level, row); check(rollback.applied == 0 && rollback.skipped == 1, "linked cancel receipt refunded items");
        }
        check(actualIds.equals(ids), "linked cancel replay changed job identities");
    }
    public void close() { if (completion != null) completion.close(); completion = null; nexus = null; cpuLink = null; requestLink = null; rows.clear(); ids.clear(); }
}
