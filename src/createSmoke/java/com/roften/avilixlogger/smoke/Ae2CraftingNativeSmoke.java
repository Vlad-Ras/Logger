package com.roften.avilixlogger.smoke;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.*;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.CraftingBlockEntity;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.*;
import appeng.menu.me.crafting.CraftingCPUMenu;
import appeng.parts.reporting.PatternAccessTerminalPart;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Actual powered CPU/job/link lifecycle with bounded test plans; no AuthCore or production emit bypass. */
public final class Ae2CraftingNativeSmoke {
    private static final RuntimeException FAULT = new RuntimeException("intentional crafting operation failure");
    private static void check(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    private record Plan(GenericStack finalOutput, long bytes, boolean simulation, boolean missing) implements ICraftingPlan {
        public boolean multiplePaths() { return false; }
        public KeyCounter usedItems() { var c = new KeyCounter(); if (missing) c.add(AEItemKey.of(Items.NETHERITE_BLOCK), 4); return c; }
        public KeyCounter emittedItems() { var c = new KeyCounter(); c.add(finalOutput.what(), finalOutput.amount()); return c; }
        public KeyCounter missingItems() { return new KeyCounter(); }
        public Map<IPatternDetails, Long> patternTimes() { return Map.of(); }
    }
    public static void run(ServerLevel level, ServerPlayer player, PatternAccessTerminalPart terminal, CraftingBlockEntity block) throws Exception {
        check(!Ae2Audit.enabled(), "crafting fixture enabled production logging");
        var cpu = Objects.requireNonNull(block.getCluster()); var grid = terminal.getGridNode().getGrid(); var service = grid.getCraftingService();
        var source = new PlayerSource(player, terminal); var previousMenu = player.containerMenu;
        var menu = new CraftingCPUMenu(CraftingCPUMenu.TYPE, 119, player.getInventory(), block); player.containerMenu = menu;
        var rows = new ArrayList<LogEntry>();
        try {
            var encoded = PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.STONE), 2)), List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 3)));
            var custom = new CompoundTag(); custom.putString("native-evidence", "v".repeat(4000)); encoded.set(DataComponents.CUSTOM_DATA, CustomData.of(custom));
            var output = new GenericStack(AEItemKey.of(encoded), 8);
            reject(service, cpu, source, new Plan(output, 64, true, false), null, "INCOMPLETE_PLAN", rows);
            reject(service, cpu, source, new Plan(output, Long.MAX_VALUE, false, false), cpu, "CPU_TOO_SMALL", rows);
            reject(service, cpu, source, new Plan(output, Long.MAX_VALUE, false, false), null, "NO_SUITABLE_CPU_FOUND", rows);
            reject(service, cpu, source, new Plan(output, 64, false, true), cpu, "MISSING_INGREDIENT", rows);
            var first = submit(service, cpu, source, new Plan(output, 64, false, false), null, rows);
            check(cpu.isBusy() && first.link() == null, "standalone submit did not create actual CPU job / unexpected requester link");
            UUID firstId = cpu.craftingLogic.getLastLink().getCraftingID();
            reject(service, cpu, source, new Plan(output, 64, false, false), cpu, "CPU_BUSY", rows);
            var cancel = Ae2CraftingAudit.inspectCancellation(menu, cpu); check(cancel != null, "busy cancellation inspector missing");
            // Callback/menu replacement must not redirect the captured CPU/menu address or actor.
            player.containerMenu = player.inventoryMenu; menu.receiveClientAction("cancelCrafting", "null");
            var cancelled = cancel.finish(); check(cancelled != null && !cpu.isBusy() && cancel.finish() == null, "native UI cancellation failed or repeated"); rows.add(cancelled);
            check(((CompoundTag)cancelled.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)).getUUID("JobId").equals(firstId), "cancellation used wrong job ID");
            check(cancelled.x == block.getBlockPos().getX() && cancelled.y == block.getBlockPos().getY() && cancelled.z == block.getBlockPos().getZ(), "cancel followed replacement menu address");
            menu.cancelCrafting(); check(Ae2CraftingAudit.inspectCancellation(menu, cpu) == null, "idle cancellation produced a job receipt");
            player.containerMenu = menu;
            var machine = new MachineSource(terminal); var fluid = new GenericStack(AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER), 5_000_000_000L);
            submit(service, cpu, machine, new Plan(fluid, 64, false, false), cpu, rows);
            var machineRow = rows.getLast(); check(machineRow.actorUuid == null && (machineRow.actorName == null || machineRow.actorName.equals("Сервер")), "machine job blamed a nearby player");
            var standalone = cpu.craftingLogic.getLastLink(); var fluidCancel = Ae2CraftingAudit.inspectLinkCancellation(standalone);
            standalone.cancel(); rows.add(Objects.requireNonNull(fluidCancel.finish()));
            check(Ae2CraftingAudit.inspectLinkCancellation(standalone) == null && Ae2CraftingAudit.inspectCpuCancellation(cpu) == null, "standalone link cleanup duplicated cancel");
            cpu.cancelJob(); // Native cleanup sees the already cancelled link, just as the next normal CPU tick does.
            check(Ae2CraftingAudit.inspectCpuCancellation(cpu) == null, "standalone CPU idle/repeat created receipt");
            var completeOutput = new GenericStack(AEItemKey.of(Items.DIAMOND), 2);
            submit(service, cpu, source, new Plan(completeOutput, 64, false, false), cpu, rows);
            var noFalseCancel = Ae2CraftingAudit.inspectCpuCancellation(cpu);
            long inserted = cpu.craftingLogic.insert(completeOutput.what(), 2, Actionable.MODULATE);
            check(inserted == 0 && !cpu.isBusy() && noFalseCancel.finish() == null, "native standalone completion/result changed or misreported as cancellation");
            submit(service, cpu, source, new Plan(output, 64, false, false), cpu, rows);
            var partialCancel = Ae2CraftingAudit.inspectCancellation(menu, cpu);
            int[] calls = {0};
            invokeCancelWrapper(menu, cpu, args -> { calls[0]++; cpu.cancelJob(); throw FAULT; });
            check(calls[0] == 1 && !cpu.isBusy(), "native cancellation operation repeated or lost before exception");
            rows.add(Objects.requireNonNull(partialCancel.finish()));
            var faultPlan = new Plan(output, 64, false, false);
            var admissionFault = Ae2CraftingAudit.inspectSubmission(faultPlan, source, cpu);
            int[] admissionCalls = {0};
            var admissionWrapper = Arrays.stream(CraftingCPUCluster.class.getDeclaredMethods()).filter(m -> m.getName().contains("avilixlogger$submitAccepted")
                    && m.getParameterCount() == 5 && m.getParameterTypes()[4] == Operation.class).findFirst().orElseThrow(); admissionWrapper.setAccessible(true);
            try {
                admissionWrapper.invoke(cpu, grid, faultPlan, source, null, (Operation<ICraftingSubmitResult>)args -> {
                    admissionCalls[0]++; check(cpu.submitJob(grid, faultPlan, source, null).successful(), "partial admission fixture refused"); throw FAULT;
                }); throw new AssertionError("partial admission fault swallowed");
            } catch (InvocationTargetException failure) { check(failure.getCause() == FAULT && admissionCalls[0] == 1, "partial admission repeated or replaced original fault"); }
            var partialAdmissionRow = admissionFault.finishAfterFailure();
            check(partialAdmissionRow != null && admissionFault.finishAfterFailure() == null && ((CompoundTag)partialAdmissionRow.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)).getBoolean("NativeThrew"), "new matching CPU job lost or repeated after failure");
            rows.add(partialAdmissionRow);
            var admissionCancel = Ae2CraftingAudit.inspectCancellation(menu, cpu); menu.cancelCrafting(); rows.add(Objects.requireNonNull(admissionCancel.finish()));
            wrappers(service, cpu, menu, source, grid);
            var errors = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = new Thread(() -> {
                try { check(Ae2CraftingAudit.inspectSubmission(poisonPlan(), poisonSource(), cpu) == null
                        && Ae2CraftingAudit.inspectCancellation(menu, cpu) == null && Ae2CraftingAudit.inspectCpuCancellation(cpu) == null, "off-thread crafting inspector touched native data"); }
                catch (Throwable failure) { errors.set(failure); }
            }); worker.start(); worker.join(5000); check(!worker.isAlive() && errors.get() == null, "off-thread inspector failed: " + errors.get());
            pipeline(level, player, terminal, rows);
            check(rows.size() == 14 && !Ae2Audit.enabled(), "unexpected job receipt count or production authorization changed");
            org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke").info("AE2_CRAFTING_NATIVE_OK: real powered CPU and standalone links, auto/explicit admission, five refusals, UI cancel/idle/repeat, direct standalone CPU-link cancel with neutral actor/full fluid key/5000000000 planned quantity and no cleanup duplicate, completed job is not cancel, partial admission/cancel evidence, native fault once, detached worker/replay; accepted=5, rejected=5, cancelled=4, rows={}; controlled plans, recipe calculation/assembler chain and authenticated emit not tested", rows.size());
        } finally { cpu.cancelJob(); menu.removed(player); player.containerMenu = previousMenu; }
    }
    private static ICraftingSubmitResult submit(ICraftingService service, CraftingCPUCluster cpu, IActionSource source, Plan plan, ICraftingCPU selected, List<LogEntry> rows) {
        var accepted = Ae2CraftingAudit.inspectSubmission(plan, source, cpu); var refused = Ae2CraftingAudit.inspectSubmission(plan, source, selected);
        check(accepted != null && refused != null, "submission data inspectors missing");
        var result = service.submitJob(plan, null, selected, true, source);
        check(result.successful(), "native test plan refused: " + result.errorCode());
        check(refused.finish(result, false) == null, "service duplicated accepted CPU receipt");
        var row = accepted.finish(result, true); check(row != null && accepted.finish(result, true) == null, "accepted job missing or repeated");
        var data = (CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER);
        check(data.getUUID("JobId").equals(cpu.craftingLogic.getLastLink().getCraftingID()) && data.contains("Cpu"), "accepted native CPU link identity missing");
        rows.add(row); return result;
    }
    private static void reject(ICraftingService service, CraftingCPUCluster cpu, IActionSource source, Plan plan, ICraftingCPU selected, String code, List<LogEntry> rows) {
        var accepted = Ae2CraftingAudit.inspectSubmission(plan, source, cpu); var refused = Ae2CraftingAudit.inspectSubmission(plan, source, selected);
        var result = service.submitJob(plan, null, selected, true, source);
        check(!result.successful() && result.errorCode().name().equals(code), "wrong native rejection: " + result.errorCode() + ", expected " + code);
        check(accepted.finish(result, true) == null, "refusal claimed CPU acceptance");
        var row = refused.finish(result, false); check(row != null && ((CompoundTag)row.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)).getString("ErrorCode").equals(code), "refusal reason lost"); rows.add(row);
    }
    private static ICraftingPlan poisonPlan() {
        return (ICraftingPlan)Proxy.newProxyInstance(ICraftingPlan.class.getClassLoader(), new Class[]{ICraftingPlan.class}, (p, m, a) -> { throw new AssertionError("disabled/off-thread audit read plan: " + m); });
    }
    private static IActionSource poisonSource() {
        return (IActionSource)Proxy.newProxyInstance(IActionSource.class.getClassLoader(), new Class[]{IActionSource.class}, (p, m, a) -> { throw new AssertionError("disabled/off-thread audit read source: " + m); });
    }
    private static void wrappers(ICraftingService service, CraftingCPUCluster cpu, CraftingCPUMenu menu, IActionSource ignored, appeng.api.networking.IGrid grid) throws Exception {
        int checked = 0;
        for (Object target : List.of(service, cpu)) for (Method method : target.getClass().getDeclaredMethods()) {
            if (!method.getName().contains("avilixlogger$submit") || method.getParameterCount() == 0
                    || method.getParameterTypes()[method.getParameterCount()-1] != Operation.class) continue;
            method.setAccessible(true); var plan = poisonPlan(); var source = poisonSource();
            Object[] nativeArgs = target == service ? new Object[]{plan, null, cpu, true, source} : new Object[]{grid, plan, source, null};
            Object[] arguments = Arrays.copyOf(nativeArgs, nativeArgs.length + 1); int[] calls = {0};
            arguments[arguments.length-1] = (Operation<Object>)args -> { calls[0]++; check(Arrays.equals(args, nativeArgs), "wrapper changed native arguments"); throw FAULT; };
            try { method.invoke(target, arguments); throw new AssertionError("submission wrapper swallowed original exception"); }
            catch (InvocationTargetException failure) { check(failure.getCause() == FAULT, "submission wrapper replaced original exception"); }
            check(calls[0] == 1, "submission wrapper repeated native call");
            var returned = appeng.crafting.execution.CraftingSubmitResult.CPU_BUSY;
            arguments[arguments.length-1] = (Operation<Object>)args -> returned;
            check(method.invoke(target, arguments) == returned, "disabled wrapper replaced native result"); checked++;
        }
        check(checked == 2, "missing transformed service/CPU submission wrappers");
        int[] calls = {0}; invokeCancelWrapper(menu, cpu, args -> { calls[0]++; throw FAULT; }); check(calls[0] == 1, "cancel wrapper repeated original");
        check(Ae2CraftingAudit.beginSubmission(poisonPlan(), poisonSource(), cpu) == null && Ae2CraftingAudit.beginCancellation(menu, cpu) == null, "disabled crafting capture returned a scope");
    }
    private static void invokeCancelWrapper(CraftingCPUMenu menu, CraftingCPUCluster cpu, Operation<Void> original) throws Exception {
        var method = Arrays.stream(CraftingCPUMenu.class.getDeclaredMethods()).filter(m -> m.getName().contains("avilixlogger$cancelJob")
                && m.getParameterCount() == 2 && m.getParameterTypes()[1] == Operation.class).findFirst().orElseThrow(); method.setAccessible(true);
        try { method.invoke(menu, cpu, original); throw new AssertionError("cancel wrapper swallowed native exception"); }
        catch (InvocationTargetException failure) { check(failure.getCause() == FAULT, "cancel wrapper replaced native exception"); }
    }
    private static void pipeline(ServerLevel level, ServerPlayer player, PatternAccessTerminalPart terminal, List<LogEntry> rows) {
        var received = new CopyOnWriteArrayList<LogEntry>();
        var storage = new AsyncLogStorage(new LogStorage() {
            public void append(LogEntry row) { received.add(row); }
            public List<LogEntry> query(LogQuery q) { return List.copyOf(received); }
            public List<LogEntry> queryReverse(LogQuery q) { return query(q); }
            public void shutdown() {}
        }, 2, 1024 * 1024);
        try { rows.forEach(storage::append); } finally { storage.shutdown(); }
        check(received.size() == rows.size(), "native job worker lost receipts");
        for (int i = 0; i < rows.size(); i++) {
            var row = received.get(i); var data = Ae2CraftingDetails.context(row);
            check(data != null && row.type == rows.get(i).type && row.source.equals(rows.get(i).source), "native receipt FIFO/evidence lost");
            if (row.type == ActionType.ME_CRAFT_CANCEL) {
                if (data.getString("CancellationOrigin").equals("cpu-link")) check(row.actorUuid == null
                        && data.getCompound("Cpu").getString("BoundsMin").equals(new net.minecraft.core.BlockPos(row.x, row.y, row.z).toShortString())
                        && Ae2CraftingDetails.summary(row).amount() == 5_000_000_000L, "standalone CPU cancellation lost neutral CPU address/long output");
                else check(row.actorUuid.equals(player.getUUID()), "cancellation attributed to old requester instead of current editor");
            }
            else check(row.x == terminal.getBlockEntity().getBlockPos().getX() && row.z == terminal.getBlockEntity().getBlockPos().getZ(), "submission used CPU/player position instead of source host");
            if (row.itemStackNbt != null) {
                var item = ItemStack.parseOptional(level.registryAccess(), NbtSerde.fromSnbt(row.itemStackNbt));
                if (item.getItem() == appeng.core.definitions.AEItems.PROCESSING_PATTERN.asItem()) check(PatternDetailsHelper.decodePattern(item, level) != null && row.itemStackNbt.length() > 4000, "native job lost encoded pattern/full components");
            } else {
                check(data.contains("Key") && Ae2CraftingDetails.summary(row).amount() == 5_000_000_000L, "machine fluid full key/long lost");
                var key = AEKey.fromTagGeneric(level.registryAccess(), data.getCompound("Key")); check(key instanceof AEFluidKey, "native fluid job key did not decode");
            }
            var replay = GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row), LogEntry.class);
            check(replay.beAfter.equals(row.beAfter) && replay.extra.equals(row.extra) && Objects.equals(replay.itemStackNbt, row.itemStackNbt), "native job replay changed full evidence");
            check(!LogText.toChatLine(level, row).getString().contains("ae2-crafting-v1"), "job GUI leaked internal prefix");
            var rollback = RollbackEngine.applyPreparedEntry(level, row);
            check(rollback.applied == 0 && rollback.skipped == 1 && rollback.itemsGivenOrSpawned == 0 && rollback.itemsRemovedFromInventory == 0, "job receipt implicitly restored items or job");
        }
    }
}
