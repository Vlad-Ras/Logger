package com.roften.avilixlogger.compat.createcobblestone;

import com.roften.avilixlogger.compat.machines.MachineAudit;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.util.*;

/** Production is coalesced between actual external mutations, at most twenty native ticks. */
public final class CobblestoneAudit {
    private static final Map<BlockEntity, Pending> PENDING = new WeakHashMap<>();
    private static final Map<BlockEntity, Long> EPOCH = new WeakHashMap<>();
    private CobblestoneAudit() {}
    public static ModStateAudit.Action begin(Object value, String operation) {
        if (!(value instanceof BlockEntity be) || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return null;
        flush(be);
        EPOCH.put(be, EPOCH.getOrDefault(be, 0L) + 1);
        return ModStateAudit.begin(((ServerLevel)be.getLevel()).getServer(), "createcobblestone", operation, be, "generator",
                ActionType.MACHINE_CONTENT_CHANGE, () -> snapshot(be));
    }
    public static ModStateAudit.Action type(Object value) {
        if (!(value instanceof BlockEntity be) || MachineAudit.level(be) == null) return null;
        flush(be); EPOCH.put(be, EPOCH.getOrDefault(be, 0L) + 1);
        return ModStateAudit.begin(((ServerLevel)be.getLevel()).getServer(), "createcobblestone", "generator-type", be, "generator",
                ActionType.MACHINE_STATE_CHANGE, () -> snapshot(be));
    }
    private static CompoundTag snapshot(BlockEntity be) throws ReflectiveOperationException {
        return snapshot(be, ((Container)be).getItem(0), StateAccess.field(be, "type"), (double)StateAccess.field(be, "available"));
    }
    private static CompoundTag snapshot(BlockEntity be, ItemStack stack, Object type, double available) throws ReflectiveOperationException {
        var tag = MachineAudit.location(be);
        if (MachineAudit.policy(be, ActionType.MACHINE_CONTENT_CHANGE)) {
            tag.put("RuntimeBuffer", stack.saveOptional(be.getLevel().registryAccess()));
            tag.putDouble("FractionalProduction", available);
        }
        if (type != null) {
            var descriptor = new CompoundTag();
            for (String field : new String[]{"id", "block", "generatorStress", "outputPerSecondPerRpm", "generatorStorage"})
                descriptor.put(field, StateAccess.encode(StateAccess.field(type, field)));
            tag.put("GeneratorType", descriptor);
        }
        tag.putBoolean("NativePersistsBuffer", false); return tag;
    }
    public static Tick tick(Object value) {
        if (!(value instanceof BlockEntity be) || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return null;
        try {
            // Native tick replaces the stack; it never edits the previous object in place.
            return new Tick(be, ((Container)be).getItem(0), StateAccess.field(be, "type"),
                    (double)StateAccess.field(be, "available"), EPOCH.getOrDefault(be, 0L));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("cobblestone-tick-before"); return null; }
    }
    public static void flush(Object value) {
        if (!(value instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return;
        var pending = PENDING.remove(be); if (pending == null) return;
        try (var cause = CauseContext.unattributed()) {
            var before = snapshot(be, pending.first, pending.type, pending.firstAvailable);
            var after = snapshot(be, pending.last, pending.type, pending.lastAvailable);
            after.putLong("ProductionFromTick", pending.start); after.putLong("ProductionToTick", pending.end);
            after.putBoolean("NativeThrew", pending.threw);
            ModStateAudit.receipt(level.getServer(), "createcobblestone", "production-interval", ActionType.MACHINE_CONTENT_CHANGE, before, after);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("cobblestone-flush"); }
    }
    public static void stop(MinecraftServer server) {
        if (!server.isSameThread()) return;
        for (var be : new ArrayList<>(PENDING.keySet())) if (be.getLevel() instanceof ServerLevel level && level.getServer() == server) flush(be);
        PENDING.clear(); EPOCH.clear();
    }
    public static final class Tick {
        private final BlockEntity be; private final ItemStack first; private final Object type;
        private final double available; private final long epoch;
        private Tick(BlockEntity be, ItemStack first, Object type, double available, long epoch) {
            this.be = be; this.first = first; this.type = type; this.available = available; this.epoch = epoch;
        }
        public void finish(boolean threw) {
            if (!MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) { PENDING.remove(be); return; }
            try {
                long now = be.getLevel().getGameTime();
                // A nested changeType has its own before/after including the cleared buffer.
                if (EPOCH.getOrDefault(be, 0L) == epoch && type == StateAccess.field(be, "type")) {
                    var last = ((Container)be).getItem(0);
                    if (!ItemStack.matches(first, last)) {
                        boolean positive = last.getCount() > first.getCount()
                                && (first.isEmpty() || ItemStack.isSameItemSameComponents(first, last));
                        if (!positive) flush(be);
                        var pending = PENDING.get(be);
                        if (pending == null) {
                            // Hard bound on retained producers, never a world scan or forced chunk load.
                            if (PENDING.size() >= 1024) {
                                var before = snapshot(be, first, type, available); var after = snapshot(be);
                                after.putBoolean("NativeThrew", threw);
                                ModStateAudit.receipt(((ServerLevel)be.getLevel()).getServer(), "createcobblestone", "production",
                                        ActionType.MACHINE_CONTENT_CHANGE, before, after); return;
                            }
                            pending = new Pending(first.copy(), type, available, now); PENDING.put(be, pending);
                        }
                        pending.last = last.copy(); pending.lastAvailable = (double)StateAccess.field(be, "available");
                        pending.end = now; pending.threw |= threw;
                        if (!positive || threw) flush(be);
                    }
                }
                var pending = PENDING.get(be);
                if (pending != null && now - pending.start >= 20) flush(be);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("cobblestone-tick-after"); }
        }
    }
    private static final class Pending {
        private final ItemStack first; private final Object type; private final double firstAvailable; private final long start;
        private ItemStack last; private double lastAvailable; private long end; private boolean threw;
        private Pending(ItemStack first, Object type, double available, long tick) {
            this.first = first; this.type = type; firstAvailable = available; start = tick;
        }
    }
}
