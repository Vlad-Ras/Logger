package com.roften.avilixlogger.compat.ae2;

import appeng.api.networking.crafting.*;
import appeng.api.networking.security.*;
import appeng.api.stacks.*;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import appeng.parts.AEBasePart;
import com.roften.avilixlogger.compat.ae2.mixin.CraftingLinkAccess;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Constant-size synchronous job receipts. No recipe/grid walk, retained job registry or tick hook. */
public final class Ae2CraftingAudit {
    private Ae2CraftingAudit() {}
    private static void failure() { AdaptiveLogDiagnostics.adapterFailure("ae2-crafting"); }
    private static boolean serverThread() {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null && server.isSameThread();
    }
    private record Context(ServerLevel level, LogEntry row, GenericStack output, CompoundTag data, boolean production) {}
    public static Submission beginSubmission(ICraftingPlan plan, IActionSource source, ICraftingCPU cpu) {
        return Ae2Audit.enabled() ? submission(plan, source, cpu, true) : null;
    }
    /** Data only: never enables capture or appends to production storage. */
    public static Submission inspectSubmission(ICraftingPlan plan, IActionSource source, ICraftingCPU cpu) {
        return submission(plan, source, cpu, false);
    }
    private static Submission submission(ICraftingPlan plan, IActionSource source, ICraftingCPU cpu, boolean production) {
        try {
            if (!serverThread() || plan == null) return null;
            var context = origin(source, plan.finalOutput(), production);
            if (context == null) return null;
            var cluster = cpu instanceof CraftingCPUCluster c ? c : null;
            cpu(context, cluster);
            return new Submission(context, cluster, cluster == null ? null : cluster.craftingLogic.getLastLink());
        } catch (Throwable invalid) { failure(); return null; }
    }
    public static Cancellation beginCancellation(AEBaseMenu menu, CraftingCPUCluster cpu) {
        return Ae2Audit.enabled() ? cancellation(menu, cpu, true) : null;
    }
    /** Data only: no player permission/authentication mutation. */
    public static Cancellation inspectCancellation(AEBaseMenu menu, CraftingCPUCluster cpu) { return cancellation(menu, cpu, false); }
    public static Cancellation beginCpuCancellation(CraftingCPUCluster cpu) {
        return Ae2Audit.enabled() ? cpuCancellation(cpu, true) : null;
    }
    /** Data only, including the current synchronous menu source; never enables production capture. */
    public static Cancellation inspectCpuCancellation(CraftingCPUCluster cpu) { return cpuCancellation(cpu, false); }
    private static Cancellation cpuCancellation(CraftingCPUCluster cluster, boolean production) {
        try {
            if (!serverThread() || cluster == null) return null;
            var link = cluster.craftingLogic.getLastLink();
            // Requester/nexus cancellation already owns its receipt before the CPU cleans up.
            if (link == null || link.isCanceled() || link.isDone()) return null;
            var menu = ((Ae2CpuCancellationSource)(Object)cluster.craftingLogic).avilixlogger$cancellationMenu();
            if (menu != null) return cancellation(menu, cluster, production);
            var context = origin(cluster.getSrc(), cluster.craftingLogic.getFinalJobOutput(), production);
            if (context == null) return null;
            cpu(context, cluster); context.data().putUUID("JobId", link.getCraftingID());
            context.data().putString("StateTransition", "cpu-cancelled-job");
            context.data().putString("CancellationOrigin", "cpu");
            return new Cancellation(context, link);
        } catch (Throwable invalid) { failure(); return null; }
    }
    public static Completion beginCompletion(CraftingCPUCluster cpu) { return Ae2Audit.enabled() ? completion(cpu, true) : null; }
    /** Native state inspector only; no production append or authorization mutation. */
    public static Completion inspectCompletion(CraftingCPUCluster cpu) { return completion(cpu, false); }
    public static LinkedCancellation beginLinkedCancellation(Ae2LinkedCancellationState state) {
        return Ae2Audit.enabled() ? linkedCancellation(state, true) : null;
    }
    /** Data only: never enables authorization or appends to production storage. */
    public static LinkedCancellation inspectLinkedCancellation(Ae2LinkedCancellationState state) { return linkedCancellation(state, false); }
    private static LinkedCancellation linkedCancellation(Ae2LinkedCancellationState state, boolean production) {
        try {
            if (!serverThread() || state == null || state.avilixlogger$cancelled() || state.avilixlogger$done()) return null;
            var request = state.avilixlogger$requestLink(); var cpuLink = state.avilixlogger$cpuLink();
            if (!(request instanceof CraftingLinkAccess requestAccess) || !(cpuLink instanceof CraftingLinkAccess cpuAccess)
                    || cpuAccess.avilixlogger$rawCancelled() || request.isDone() || cpuLink.isDone()) return null;
            // CPU-initiated cancellation sets its own flag first. Its finishJob(false) scope owns that receipt.
            if (!(cpuAccess.avilixlogger$cpu() instanceof CraftingCPUCluster cluster)
                    || cluster.craftingLogic.getLastLink() != cpuLink || !cpuLink.getCraftingID().equals(request.getCraftingID())) return null;
            var requester = requestAccess.avilixlogger$requester(); if (requester == null) return null;
            var context = origin(new appeng.me.helpers.MachineSource(requester), cluster.craftingLogic.getFinalJobOutput(), production);
            if (context == null) return null;
            cpu(context, cluster); context.data().putUUID("JobId", request.getCraftingID());
            context.data().putString("StateTransition", "linked-job-cancelled");
            context.data().putString("CancellationOrigin", "linked-job");
            return new LinkedCancellation(context, state, request, cpuLink);
        } catch (Throwable invalid) { failure(); return null; }
    }
    private static Completion completion(CraftingCPUCluster cluster, boolean production) {
        try {
            if (!serverThread() || cluster == null) return null;
            var link = cluster.craftingLogic.getLastLink();
            if (link == null || link.isCanceled()) return null;
            var context = origin(cluster.getSrc(), cluster.craftingLogic.getFinalJobOutput(), production);
            if (context == null) return null;
            cpu(context, cluster); context.data().putUUID("JobId", link.getCraftingID());
            context.data().putString("StateTransition", "cpu-success-cleared-job");
            return new Completion(context, cluster, link);
        } catch (Throwable invalid) { failure(); return null; }
    }
    private static Cancellation cancellation(AEBaseMenu menu, CraftingCPUCluster cluster, boolean production) {
        try {
            if (!serverThread() || cluster == null || !Ae2Audit.onServerThread(menu)) return null;
            var link = cluster.craftingLogic.getLastLink();
            if (link == null || link.isCanceled() || link.isDone()) return null;
            var source = new appeng.me.helpers.PlayerSource(menu.getPlayer(), menu.getTarget() instanceof IActionHost host ? host : null);
            var context = origin(source, cluster.craftingLogic.getFinalJobOutput(), production);
            if (context == null) return null;
            cpu(context, cluster);
            context.data().putUUID("JobId", link.getCraftingID());
            context.data().putString("StateTransition", "cpu-cancelled-job");
            context.data().putString("CancellationOrigin", "cpu-menu");
            return new Cancellation(context, link);
        } catch (Throwable invalid) { failure(); return null; }
    }
    private static Context origin(IActionSource source, GenericStack output, boolean production) {
        if (source == null || output == null || output.what() == null || output.amount() <= 0) return null;
        var player = source.player().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).orElse(null);
        Object owner = source.machine().orElse(null);
        if (owner instanceof IActionHost host && !(owner instanceof BlockEntity) && !(owner instanceof AEBasePart)) {
            var node = host.getActionableNode(); owner = node == null ? null : node.getOwner();
        }
        var block = owner instanceof BlockEntity be ? be : owner instanceof AEBasePart part ? part.getBlockEntity() : null;
        ServerLevel level = block != null && block.getLevel() instanceof ServerLevel sl ? sl
                : player != null && player.level() instanceof ServerLevel sl ? sl : null;
        if (level == null || level.getServer() != net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer()) return null;
        BlockPos pos = block == null ? player.blockPosition() : block.getBlockPos();
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.dim = level.dimension().location().toString();
        row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ(); row.source = "ae2:crafting";
        if (player != null) { row.actorUuid = CauseContext.actorUuid(player); row.actorName = CauseContext.actorName(player); }
        var data = new CompoundTag();
        data.putString("Origin", owner == null ? "player" : owner.getClass().getSimpleName());
        data.putString("OriginKind", block == null ? "player-position" : owner instanceof AEBasePart ? "part" : "block");
        return new Context(level, row, output, data, production);
    }
    private static void cpu(Context context, CraftingCPUCluster cluster) {
        if (cluster == null) return;
        var level = cluster.getLevel();
        if (!(level instanceof ServerLevel serverLevel) || serverLevel.getServer() != context.level().getServer()) return;
        var tag = new CompoundTag(); tag.putString("Dimension", level.dimension().location().toString());
        tag.putString("BoundsMin", cluster.getBoundsMin().toShortString()); tag.putString("BoundsMax", cluster.getBoundsMax().toShortString());
        var name = cluster.getName(); if (name != null) tag.putString("Name", name.getString());
        context.data().put("Cpu", tag);
    }
    private static LogEntry finish(Context context, String outcome) {
        if (!serverThread() || context.production() && !Ae2Audit.enabled()) return null;
        var output = context.output(); var key = output.what(); var type = key.getType(); var row = context.row();
        row.type = outcome.equals("finished") ? ActionType.ME_CRAFT_FINISH : outcome.equals("cancelled") ? ActionType.ME_CRAFT_CANCEL : ActionType.ME_CRAFT_REQUEST;
        row.source += outcome.equals("finished") ? ":finish" : outcome.equals("cancelled") ? ":cancel" : ":submit";
        String kind = key instanceof AEItemKey ? "i" : key instanceof AEFluidKey ? "f" : "g";
        if (key instanceof AEItemKey item) {
            var tag = NbtSerde.snapshotItemStack(item.toStack(), context.level().registryAccess());
            if (tag == null) throw new IllegalStateException("AE2 job item unavailable");
            row.deferSnapshot(LogEntry.SnapshotField.ITEM, tag); row.count = (int)Math.min(Integer.MAX_VALUE, output.amount());
        } else context.data().put("Key", key.toTagGeneric(context.level().registryAccess()).copy());
        Ae2CraftingDetails.attach(row, kind, key.getId().toString(), type.getId().toString(), output.amount(), outcome, context.data());
        if (context.production()) LoggerRuntime.storage(context.level()).append(row);
        return row;
    }
    public static final class Submission implements AutoCloseable {
        private Context context;
        private CraftingCPUCluster cluster;
        private ICraftingLink previous;
        private Submission(Context context, CraftingCPUCluster cluster, ICraftingLink previous) { this.context = context; this.cluster = cluster; this.previous = previous; }
        /** Accepted is emitted at the actual CPU; refusal at the service. No duplicate success receipt. */
        public LogEntry finish(ICraftingSubmitResult result, boolean acceptedOnly) {
            try {
                if (context == null || !serverThread() || result == null || result.successful() != acceptedOnly) return null;
                if (acceptedOnly) {
                    var link = cluster == null ? null : cluster.craftingLogic.getLastLink();
                    if (link != null && link != previous) context.data().putUUID("JobId", link.getCraftingID());
                } else {
                    var code = result.errorCode(); context.data().putString("ErrorCode", code == null ? "UNKNOWN" : code.name());
                }
                return Ae2CraftingAudit.finish(context, acceptedOnly ? "accepted" : "rejected");
            } catch (Throwable invalid) { failure(); return null; } finally { close(); }
        }
        /** A native exception can follow admission. Only a new, matching live CPU job proves it. */
        public LogEntry finishAfterFailure() {
            try {
                if (context == null || !serverThread() || cluster == null) return null;
                var link = cluster.craftingLogic.getLastLink();
                if (link == null || link == previous || !context.output().equals(cluster.craftingLogic.getFinalJobOutput())) return null;
                context.data().putUUID("JobId", link.getCraftingID()); context.data().putBoolean("NativeThrew", true);
                return Ae2CraftingAudit.finish(context, "accepted");
            } catch (Throwable invalid) { failure(); return null; } finally { close(); }
        }
        public void close() { context = null; cluster = null; previous = null; }
    }
    public static final class Cancellation implements AutoCloseable {
        private Context context;
        private ICraftingLink link;
        private Cancellation(Context context, ICraftingLink link) { this.context = context; this.link = link; }
        public LogEntry finish() {
            return finish(true);
        }
        public LogEntry finish(boolean returnedNormally) {
            try {
                if (context == null || !serverThread() || !link.isCanceled() || link.isDone()) return null;
                if (!returnedNormally) context.data().putBoolean("NativeThrew", true);
                return Ae2CraftingAudit.finish(context, "cancelled");
            }
            catch (Throwable invalid) { failure(); return null; } finally { close(); }
        }
        public void close() { context = null; link = null; }
    }
    public static final class Completion implements AutoCloseable {
        private Context context;
        private CraftingCPUCluster cluster;
        private ICraftingLink link;
        private Completion(Context context, CraftingCPUCluster cluster, ICraftingLink link) { this.context = context; this.cluster = cluster; this.link = link; }
        public LogEntry finish(boolean returnedNormally) {
            try {
                if (context == null || !serverThread() || link.isCanceled() || cluster.craftingLogic.getLastLink() == link) return null;
                if (!returnedNormally) context.data().putBoolean("NativeThrew", true);
                return Ae2CraftingAudit.finish(context, "finished");
            } catch (Throwable invalid) { failure(); return null; } finally { close(); }
        }
        public void close() { context = null; cluster = null; link = null; }
    }
    public static final class LinkedCancellation implements AutoCloseable {
        private Context context;
        private Ae2LinkedCancellationState state;
        private ICraftingLink request, cpu;
        private LinkedCancellation(Context context, Ae2LinkedCancellationState state, ICraftingLink request, ICraftingLink cpu) {
            this.context = context; this.state = state; this.request = request; this.cpu = cpu;
        }
        public LogEntry finish(boolean returnedNormally) {
            try {
                if (context == null || !serverThread() || !state.avilixlogger$cancelled() || state.avilixlogger$done()
                        || !request.isCanceled() || request.isDone() || !cpu.isCanceled() || cpu.isDone()) return null;
                if (!returnedNormally) context.data().putBoolean("NativeThrew", true);
                return Ae2CraftingAudit.finish(context, "cancelled");
            } catch (Throwable invalid) { failure(); return null; } finally { close(); }
        }
        public void close() { context = null; state = null; request = null; cpu = null; }
    }
}
