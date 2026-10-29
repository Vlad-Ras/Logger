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

/** Ten autonomous jobs, including direct link cancellation and a real disconnected requester. */
public final class Ae2RequesterCancelNativeSmoke implements AutoCloseable {
    private static final RuntimeException FAULT = new RuntimeException("intentional linked cancellation fault after native transition");
    private final ServerLevel level;
    private final ServerPlayer player;
    private final InterfaceBlockEntity requester;
    private final CraftingBlockEntity block;
    private final AbstractFurnaceBlockEntity furnace;
    private final appeng.parts.automation.ImportBusPart importer;
    private final List<LogEntry> rows = new ArrayList<>();
    private final Set<UUID> ids = new HashSet<>();
    private ICraftingLink requestLink, cpuLink;
    private Ae2LinkedCancellationState nexus;
    private Ae2CraftingAudit.Completion completion;
    private Ae2CraftingAudit.LinkedCancellation disconnected;
    private appeng.api.networking.IGridNode peer;
    private boolean detached;
    private int disconnectedAt;
    private int mode, phase, ticks, stable;
    public Ae2RequesterCancelNativeSmoke(ServerLevel level, ServerPlayer player, InterfaceBlockEntity requester, CraftingBlockEntity block, AbstractFurnaceBlockEntity furnace, appeng.parts.automation.ImportBusPart importer) {
        this.level = level; this.player = player; this.requester = requester; this.block = block; this.furnace = furnace; this.importer = importer;
    }
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private appeng.me.cluster.implementations.CraftingCPUCluster cpu() { return block.getCluster(); }
    private long count(Item item) { return cpu().getGrid().getStorageService().getInventory().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, new MachineSource(requester)); }
    private long stock() { return requester.getInterfaceLogic().getStorage().getAmount(0); }
    public String status() { return "cancelMode=" + mode + " cancelPhase=" + phase + " ticks=" + ticks + " detached=" + detached
            + " nexus=" + (nexus == null ? "none" : nexus.avilixlogger$cancelled() + "/" + nexus.avilixlogger$done() + "/requestMissing=" + (nexus.avilixlogger$requestLink() == null)); }
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
    private void cpuFault(boolean nativeCancel) throws Exception {
        var logic = cpu().craftingLogic;
        var method = Arrays.stream(logic.getClass().getDeclaredMethods()).filter(m -> m.getName().contains("avilixlogger$finishJob")
                && m.getParameterCount() == 2 && m.getParameterTypes()[1] == Operation.class).findFirst().orElseThrow(); method.setAccessible(true);
        int[] calls = {0};
        try { method.invoke(logic, false, (Operation<Void>)args -> {
            check(args.length == 1 && Boolean.FALSE.equals(args[0]), "CPU wrapper changed cancellation argument"); calls[0]++;
            if (nativeCancel) logic.cancel();
            throw FAULT;
        }); throw new AssertionError("CPU finish wrapper swallowed original fault"); }
        catch (InvocationTargetException failure) { check(failure.getCause() == FAULT && calls[0] == 1, "CPU finish wrapper replaced/repeated original fault"); }
    }
    private void linkFault(boolean nativeCancel) throws Exception {
        var method = Arrays.stream(cpuLink.getClass().getDeclaredMethods()).filter(m -> m.getName().contains("avilixlogger$linkCancel")
                && m.getParameterCount() == 1 && m.getParameterTypes()[0] == Operation.class).findFirst().orElseThrow(); method.setAccessible(true);
        int[] calls = {0};
        try { method.invoke(cpuLink, (Operation<Void>)args -> {
            check(args.length == 0, "link wrapper changed original arguments"); calls[0]++;
            if (nativeCancel) cpuLink.cancel();
            throw FAULT;
        }); throw new AssertionError("link wrapper swallowed original fault"); }
        catch (InvocationTargetException failure) { check(failure.getCause() == FAULT && calls[0] == 1, "link wrapper replaced/repeated original fault"); }
    }
    public boolean tick() throws Exception {
        check(++ticks <= 3800, "requester cancellation timeout mode=" + mode + " phase=" + phase + " cell=" + count(Items.IRON_INGOT) + " stock=" + stock());
        check(!Ae2Audit.enabled(), "requester fixture enabled production capture");
        if (phase == 0) {
            if (!cpu().isBusy()) return false;
            cpuLink = Objects.requireNonNull(cpu().craftingLogic.getLastLink());
            var links = requester.getInterfaceLogic().getRequestedJobs(); check(links.size() == 1, "autonomous cancel job has no requester link"); requestLink = links.iterator().next();
            check(ids.add(cpuLink.getCraftingID()) && requestLink != cpuLink && requestLink.getCraftingID().equals(cpuLink.getCraftingID())
                    && !cpuLink.isStandalone(), "cancel fixture reused a job or wrong link pair");
            nexus = nexus(cpuLink); check(nexus.avilixlogger$cpuLink() == cpuLink && nexus.avilixlogger$requestLink() == requestLink, "nexus view changed native pair");
            var early = Objects.requireNonNull(Ae2CraftingAudit.inspectLinkedCancellation(nexus)); check(early.finish(true) == null, "running nexus fabricated cancel");
            var earlyCpu = Objects.requireNonNull(Ae2CraftingAudit.inspectCpuCancellation(cpu())); check(earlyCpu.finish(false) == null, "running CPU fabricated cancelled fault");
            var earlyLink = Objects.requireNonNull(Ae2CraftingAudit.inspectLinkCancellation(cpuLink)); check(earlyLink.finish(false) == null, "running link fabricated cancelled fault");
            check(Ae2CraftingAudit.inspectLinkCancellation(requestLink) == null, "requester link also claimed CPU receipt");
            completion = Objects.requireNonNull(Ae2CraftingAudit.inspectCompletion(cpu()));
            if (mode == 0) { fault(false); check(!nexus.avilixlogger$cancelled() && cpu().isBusy(), "fault-only wrapper changed native state"); offThread(); }
            if (mode == 4) { cpuFault(false); check(cpu().isBusy() && !cpuLink.isCanceled(), "fault-only CPU wrapper changed native job"); }
            if (mode == 7) {
                linkFault(false); check(cpu().isBusy() && !cpuLink.isCanceled(), "fault-only link wrapper changed native job");
                var scope = (Ae2CpuCancellationSource)(Object)cpu().craftingLogic;
                boolean previous = scope.avilixlogger$swapCancellationOwned(true); check(!previous, "stale CPU capture owner");
                try { check(Ae2CraftingAudit.inspectLinkCancellation(cpuLink) == null && Ae2CraftingAudit.inspectCpuCancellation(cpu()) == null, "nested CPU/link capture duplicated owner"); }
                finally { scope.avilixlogger$swapCancellationOwned(previous); }
                check(!scope.avilixlogger$cancellationOwned(), "capture ownership retained after scope");
            }
            phase = 1; return false;
        }
        if (phase == 1) {
            if (mode == 9 && detached) {
                if (!nexus.avilixlogger$cancelled() || cpu().isBusy()) return false;
                check(ticks - disconnectedAt >= 60 && nexus.avilixlogger$requestLink() == null, "requester expired without native missing-peer grace");
            }
            if (!detached) {
                if (furnace.getItem(0).getCount() != 2) return false;
                check(furnace.getItem(0).is(Items.RAW_IRON) && furnace.getItem(2).isEmpty() && stock() == 0, "cancel point was not dispatched raw inputs");
            }
            var capture = mode < 3 ? Objects.requireNonNull(Ae2CraftingAudit.inspectLinkedCancellation(nexus)) : null;
            var cpuCapture = mode > 3 && mode < 7 ? Objects.requireNonNull(Ae2CraftingAudit.inspectCpuCancellation(cpu())) : null;
            var linkCapture = mode == 7 || mode == 8 ? Objects.requireNonNull(Ae2CraftingAudit.inspectLinkCancellation(cpuLink)) : null;
            LogEntry row;
            if (mode == 0) requester.getInterfaceLogic().getUpgrades().setItemDirect(0, ItemStack.EMPTY);
            else if (mode == 1) requestLink.cancel();
            else if (mode == 2) fault(true);
            else if (mode == 4) cpu().cancelJob();
            else if (mode == 5) cpu().craftingLogic.cancel();
            else if (mode == 6) cpuFault(true);
            else if (mode == 7) cpuLink.cancel();
            else if (mode == 8) linkFault(true);
            else if (mode == 9 && !detached) {
                // Hold native output delivery while expiry is pending; furnace ticks remain unchanged.
                importer.getConfig().setStack(0, new GenericStack(AEItemKey.of(Items.DIAMOND), 0));
                var node = requester.getMainNode().getNode(); var connections = node.getConnections();
                check(connections.size() == 1, "requester is not attached by one known dev connection");
                var connection = connections.getFirst(); peer = connection.getOtherSide(node); connection.destroy();
                check(requester.getMainNode().getGrid() != cpu().getGrid() && nexus.avilixlogger$requestLink() == null && !cpuLink.isCanceled(), "native grid split did not detach requester link");
                requester.getInterfaceLogic().getUpgrades().setItemDirect(0, ItemStack.EMPTY);
                disconnected = Objects.requireNonNull(Ae2CraftingAudit.inspectLinkedCancellation(nexus));
                detached = true; disconnectedAt = ticks; return false;
            }
            if (mode == 3) {
                var previous = player.containerMenu; var menu = new CraftingCPUMenu(CraftingCPUMenu.TYPE, 128, player.getInventory(), block); player.containerMenu = menu;
                var scope = (Ae2CpuCancellationSource)(Object)cpu().craftingLogic;
                var old = scope.avilixlogger$swapCancellationMenu(menu); check(old == null, "CPU retained a stale menu source");
                try {
                    check(scope.avilixlogger$swapCancellationMenu(null) == menu && scope.avilixlogger$swapCancellationMenu(menu) == null, "nested menu scope changed previous source");
                    var ui = Objects.requireNonNull(Ae2CraftingAudit.inspectCpuCancellation(cpu()));
                    menu.receiveClientAction("cancelCrafting", "null"); row = Objects.requireNonNull(ui.finish()); check(ui.finish() == null, "paired UI cancellation repeated receipt");
                } finally { scope.avilixlogger$swapCancellationMenu(old); menu.removed(player); player.containerMenu = previous; }
                check(scope.avilixlogger$cancellationMenu() == null, "CPU retained the player/menu after native call");
            } else if (mode == 9) {
                row = Objects.requireNonNull(disconnected.finish(true)); check(disconnected.finish(true) == null, "missing-peer cancel repeated receipt"); disconnected = null;
                appeng.api.networking.GridHelper.createConnection(requester.getMainNode().getNode(), peer);
                check(requester.getMainNode().getGrid() == cpu().getGrid(), "requester did not return to original grid");
                importer.getConfig().setStack(0, null);
                peer = null;
            } else if (mode >= 7) { row = Objects.requireNonNull(linkCapture.finish(mode != 8)); check(linkCapture.finish(true) == null, "link cancellation repeated receipt"); }
            else if (mode > 3) { row = Objects.requireNonNull(cpuCapture.finish(mode != 6)); check(cpuCapture.finish(true) == null, "CPU cancellation repeated receipt"); }
            else { row = Objects.requireNonNull(capture.finish(mode != 2)); check(capture.finish(true) == null, "linked cancellation repeated receipt"); }
            check(completion.finish(true) == null, "linked cancellation reported success"); completion = null;
            var tag = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
            var address = mode >= 3 ? block.getBlockPos() : requester.getBlockPos();
            check(row.type == ActionType.ME_CRAFT_CANCEL && (mode == 3 ? player.getUUID().equals(row.actorUuid) : row.actorUuid == null) && tag.getUUID("JobId").equals(cpuLink.getCraftingID())
                    && tag.getLong("Amount") == 2 && tag.getString("CancellationOrigin").equals(mode < 3 || mode == 9 ? "linked-job" : mode == 3 ? "cpu-menu" : mode >= 7 ? "cpu-link" : "cpu")
                    && tag.getBoolean("RequestLinkMissing") == (mode == 9) && tag.getBoolean("NativeThrew") == (mode == 2 || mode == 6 || mode == 8)
                    && row.x == address.getX() && row.y == address.getY() && row.z == address.getZ(), "linked cancel lost machine/job/planned quantity/failure evidence");
            rows.add(row);
            check(requestLink.isCanceled() && cpuLink.isCanceled() && !requestLink.isDone() && requester.getInterfaceLogic().getRequestedJobs().isEmpty(), "native cancellation did not propagate/release request link");
            check(Ae2CraftingAudit.inspectLinkedCancellation(nexus) == null && ((CraftingLinkAccess)cpuLink).avilixlogger$rawCancelled(), "cancelled nexus retained eligible capture");
            check(Ae2CraftingAudit.inspectCpuCancellation(cpu()) == null, "already cancelled CPU created second receipt");
            check(Ae2CraftingAudit.inspectLinkCancellation(cpuLink) == null, "already cancelled link created second receipt");
            requestLink.cancel(); check(Ae2CraftingAudit.inspectLinkedCancellation(nexus) == null, "repeat native cancellation fabricated receipt");
            requester.getInterfaceLogic().getUpgrades().setItemDirect(0, ItemStack.EMPTY); // Prevent a replacement craft job; ordinary restocking remains enabled.
            phase = 2; stable = 0; return false;
        }
        if (phase == 2) {
            if (cpu().isBusy() || !furnace.getItem(0).isEmpty() || !furnace.getItem(2).isEmpty() || stock() != 2) return false;
            check(count(Items.RAW_IRON) == 0 && count(Items.IRON_INGOT) == 0 && requester.getInterfaceLogic().getRequestedJobs().isEmpty()
                    && Ae2CraftingAudit.inspectCompletion(cpu()) == null, "cancelled job resurrected or changed delivered balance");
            if (++stable < 25) return false;
            if (++mode < 10) { setup(); phase = 0; return false; }
            pipeline(); check(rows.size() == 10 && ids.size() == 10, "linked/CPU cancel case count changed");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_REQUESTER_CANCEL_NATIVE_OK: retained card/requester/nexus/UI/CPU/logic/fault cases plus raw CPU link, link fault and real requester grid split/native expiry; ten autonomous jobs, dispatched raw iron still reaches stock after cancellation and reconnect; machineRows=9, uiRows=1, rows=10; inspector-only, authenticated production emit/dedup not tested");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_CPU_CANCEL_NATIVE_OK: CPU/logic direct cancellation, post-transition fault once, running/idle/repeat refusal, no false finish, original job IDs and planned output, CPU versus requester coordinates, call-scoped UI actor restored, worker/Gzip/details and rollback refusal; three new real recipe jobs; authenticated production append not tested");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_LINK_CANCEL_NATIVE_OK: raw CPU link API and same original fault once; missing requester after real IGridConnection.destroy, native grace/expiry then CPU cleanup, neutral CPU address and RequestLinkMissing, real reconnect and exact stock balance; owner scope blocks nested CPU/link inspectors, off-thread/disabled guards; three new autonomous jobs; data-only, authenticated production append/dedup not tested");
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
        check(Ae2CraftingAudit.beginCpuCancellation(cpu()) == null, "disabled CPU production capture admitted job");
        var poisonLink = (ICraftingLink)Proxy.newProxyInstance(ICraftingLink.class.getClassLoader(), new Class[]{ICraftingLink.class}, (p, m, args) -> { throw new AssertionError("disabled/off-thread link read"); });
        check(Ae2CraftingAudit.beginLinkCancellation(poisonLink) == null, "disabled link capture read native data");
        var error = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var cluster = cpu();
        var thread = new Thread(() -> { try { check(Ae2CraftingAudit.inspectLinkedCancellation(poison) == null && Ae2CraftingAudit.inspectCpuCancellation(cluster) == null && Ae2CraftingAudit.inspectLinkCancellation(poisonLink) == null, "off-thread cancellation capture accepted"); } catch (Throwable failure) { error.set(failure); } });
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
        check(received.size() == 10, "linked/CPU cancel worker dropped rows");
        var actualIds = new HashSet<UUID>();
        for (int i = 0; i < received.size(); i++) {
            var row = received.get(i); var detail = Ae2CraftingDetails.context(row);
            check(detail != null && row.type == ActionType.ME_CRAFT_CANCEL && detail.getLong("Amount") == 2 && actualIds.add(detail.getUUID("JobId"))
                    && detail.getBoolean("NativeThrew") == (i == 2 || i == 6 || i == 8) && detail.getBoolean("RequestLinkMissing") == (i == 9)
                    && row.itemStackNbt != null, "linked cancel worker lost ordering/full item/identity");
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(row.beAfter.equals(replay.beAfter) && row.itemStackNbt.equals(replay.itemStackNbt), "linked cancel Gzip lost evidence");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-crafting-v1"), "linked cancel UI leaked prefix");
            if (i == 2 || i == 6 || i == 8) check(Ae2CraftingDetails.describe(row).stream().anyMatch(c -> c.getString().contains("Отмена подтверждена")), "cancellation fault described as job creation");
            var rollback = RollbackEngine.applyPreparedEntry(level, row); check(rollback.applied == 0 && rollback.skipped == 1, "linked cancel receipt refunded items");
        }
        check(actualIds.equals(ids), "linked cancel replay changed job identities");
    }
    public void close() { if (completion != null) completion.close(); if (disconnected != null) disconnected.close(); completion = null; disconnected = null; peer = null; nexus = null; cpuLink = null; requestLink = null; rows.clear(); ids.clear(); }
}
