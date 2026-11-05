package com.roften.avilixlogger.compat.vibrantvaults;

import com.mojang.serialization.Codec;
import com.roften.avilixlogger.compat.machines.*;
import com.roften.avilixlogger.compat.teams.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.lang.ref.WeakReference;
import java.util.*;

/** Native physical storage and finite raw settings; no BE serializer (frogport write mutates flags). */
public final class VibrantVaultAudit {
    private static final Map<Object, WeakReference<BlockEntity>> OWNERS = new WeakHashMap<>();
    private VibrantVaultAudit() {}
    private static String kind(BlockEntity be) {
        String type = be.getClass().getSimpleName();
        if (type.equals("FactoryPanelBlockEntity")) return "panel";
        String candidate = switch (type) {
            case "ItemVaultBlockEntity" -> "vault";
            case "PackagerBlockEntity" -> "packager";
            case "FrogportBlockEntity" -> "port";
            case "PackagerLinkBlockEntity" -> "link";
            case "RedstoneRequesterBlockEntity" -> "requester";
            default -> "";
        };
        return !candidate.isEmpty() && BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock())
                .getNamespace().equals("create_vibrant_vaults") ? candidate : "";
    }
    public static boolean handles(Object target) {
        return target instanceof MachineState && target instanceof BlockEntity be && !kind(be).isEmpty() && MachineAudit.level(be) != null;
    }
    private static IItemHandler inventory(BlockEntity be) throws ReflectiveOperationException {
        return switch (kind(be)) {
            case "vault", "packager", "port" -> (IItemHandler) StateAccess.field(be, "inventory");
            default -> null;
        };
    }
    public static void attached(Object target) {
        // setLevel runs after construction, sometimes before the chunk installs the BE.
        // Binding reads only the physical handler; actual capture still requires world identity.
        if (!(target instanceof MachineState) || !(target instanceof BlockEntity be)
                || !(be.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread() || kind(be).isEmpty()) return;
        bindPhysical(be);
    }
    public static void bind(Object target) {
        if (!handles(target)) return;
        bindPhysical((BlockEntity)target);
    }
    private static void bindPhysical(BlockEntity be) {
        try {
            var inventory = inventory(be);
            if (inventory != null) {
                OWNERS.put(inventory, new WeakReference<>(be));
                // SmartInventory delegates to its own ItemStackHandler, also used directly by native menus.
                if (kind(be).equals("port")) OWNERS.put(StateAccess.field(inventory, "wrapped"), new WeakReference<>(be));
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-bind"); }
    }
    public static void baseline(Object target) {
        if (!handles(target) || !(target instanceof MachineState tracked)) return;
        var be = (BlockEntity) target;
        bind(be);
        try { tracked.avilixlogger$machineBaseline(MachineAudit.enabled(be, observationType(be)) ? state(be) : null); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { tracked.avilixlogger$machineBaseline(null); MachineAudit.failure("vibrant-baseline"); }
    }
    public static void observed(Object target) { observed(target, false); }
    private static void observed(Object target, boolean threw) {
        if (!(target instanceof MachineState tracked) || !(target instanceof BlockEntity be)
                || kind(be).isEmpty()) return;
        var level = MachineAudit.level(be);
        if (level == null) return;
        bindPhysical(be);
        if (ModStateAudit.capturing(be, "machine")) return;
        var type = observationType(be);
        if (!ModStateAudit.enabled(level.getServer(), type)) { tracked.avilixlogger$machineBaseline(null); return; }
        try {
            var previous = tracked.avilixlogger$machineBaseline(); var now = state(be); tracked.avilixlogger$machineBaseline(now);
            if (previous != null && !previous.equals(now)) {
                // A dirty notification does not establish the author of an earlier raw-field edit.
                try (var cause = CauseContext.unattributed()) {
                    ModStateAudit.receipt(level.getServer(), "create_vibrant_vaults",
                            "native-state-observation", type, previous, evidence(now, threw));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-observed"); }
    }
    public static Action begin(Object target, String operation, boolean contents) {
        if (!handles(target)) return null;
        var be = (BlockEntity)target; bind(be);
        var type = contents ? ActionType.MACHINE_CONTENT_CHANGE : ActionType.MACHINE_STATE_CHANGE;
        if (!MachineAudit.enabled(be, type)) return null;
        var action = ModStateAudit.begin(((ServerLevel)be.getLevel()).getServer(), "create_vibrant_vaults", operation,
                be, "machine", type, () -> snapshot(be, contents));
        return action == null ? null : new Action(be, action);
    }
    private static BlockEntity menuTarget(Object menu) throws ReflectiveOperationException {
        if (menu == null || !menu.getClass().getName().equals("com.simibubi.create.content.logistics.packagePort.PackagePortMenu")) return null;
        Object holder = StateAccess.field(menu, "contentHolder"); return handles(holder) ? (BlockEntity)holder : null;
    }
    public static boolean opened(net.minecraft.server.level.ServerPlayer player, Object menu) {
        try {
            var be = menuTarget(menu);
            if (be == null || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return false;
            baseline(be);
            var before = MachineAudit.location(be); var after = before.copy(); after.putString("Menu", menu.getClass().getName());
            try (var cause = CauseContext.push(player, CauseContext.Kind.OTHER, be.getBlockPos(), ItemStack.EMPTY)) {
                ModStateAudit.receipt(player.server, "create_vibrant_vaults", "menu-open", ActionType.CONTAINER_OPEN, before, after);
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-menu-open"); return false; }
    }
    public static Action menu(net.minecraft.server.level.ServerPlayer player, String operation) {
        try {
            var menu = player.containerMenu; var be = menuTarget(menu);
            if (be == null || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return null;
            bind(be);
            var action = ModStateAudit.begin(player.server, "create_vibrant_vaults", operation, be, "machine", ActionType.MACHINE_CONTENT_CHANGE, () -> {
                var tag = snapshot(be, true);
                tag.put("PlayerInventory", EntityContainerSerde.snapshot(player.getInventory(), player.registryAccess()));
                tag.put("Cursor", menu.getCarried().saveOptional(player.registryAccess()));
                int[] properties = GenericMenuSnapshot.properties(menu); if (properties != null) tag.putIntArray("Properties", properties);
                tag.putString("Menu", menu.getClass().getName()); return tag;
            });
            return action == null ? null : new Action(be, action);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-menu"); return null; }
    }
    public static Action item(Object handler, int slot, boolean simulate) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (simulate || server == null || !server.isSameThread()) return null;
        var owner = OWNERS.get(handler); var be = owner == null ? null : owner.get();
        if (be == null || !handles(be) || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return null;
        try {
            var inventory = inventory(be);
            if (inventory == null || slot < 0 || slot >= inventory.getSlots()) return null;
            var action = ModStateAudit.begin(server, "create_vibrant_vaults", "physical-slot", be, "machine",
                    ActionType.MACHINE_CONTENT_CHANGE, () -> {
                        // Unwrapping may change package queues without ever storing the inserted package.
                        if (kind(be).equals("packager")) return snapshot(be, true);
                        var tag = MachineAudit.location(be); tag.putInt("Slot", slot);
                        tag.put("Stack", inventory.getStackInSlot(slot).saveOptional(be.getLevel().registryAccess())); return tag;
                    });
            return action == null ? null : new Action(be, action);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-slot"); return null; }
    }
    private static ActionType observationType(BlockEntity be) {
        String kind = kind(be);
        return (kind.equals("packager") || kind.equals("port")) && MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)
                ? ActionType.MACHINE_CONTENT_CHANGE : ActionType.MACHINE_STATE_CHANGE;
    }
    public static Tick tick(Object value) {
        if (!(value instanceof BlockEntity be) || !handles(be) || !MachineAudit.enabled(be, ActionType.MACHINE_CONTENT_CHANGE)) return null;
        try { return new Tick(be, marker(be)); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-tick-before"); return null; }
    }
    private static Marker marker(BlockEntity be) throws ReflectiveOperationException {
        if (kind(be).equals("port")) return new Marker(StateAccess.field(be, "animatedPackage"), null, 0, 0);
        var queue = (List<?>)StateAccess.field(be, "queuedExitingPackages");
        return new Marker(StateAccess.field(be, "heldBox"), StateAccess.field(be, "previouslyUnwrapped"), queue.size(),
                queue.isEmpty() ? 0 : (int)StateAccess.field(queue.getFirst(), "count"));
    }
    private record Marker(Object held, Object previous, int queued, int count) {}
    public static final class Tick {
        private final BlockEntity be; private final Marker before;
        private Tick(BlockEntity be, Marker before) { this.be = be; this.before = before; }
        public void finish(boolean threw) {
            try { if (!before.equals(marker(be))) observed(be, threw); }
            catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-tick-after"); }
        }
    }
    private static CompoundTag evidence(CompoundTag state, boolean threw) {
        var copy = state.copy(); copy.putBoolean("NativeThrew", threw); return copy;
    }
    public static Submission request(Object target, Object order, String address) {
        if (!handles(target)) return null;
        var be = (BlockEntity)target;
        if (!MachineAudit.enabled(be, ActionType.MACHINE_STATE_CHANGE)) return null;
        try {
            var before = state(be); before.put("SubmittedOrder", codec(be, order, "com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts"));
            before.putString("SubmittedAddress", address); before.putString("Submission", "submitted");
            return new Submission(((ServerLevel)be.getLevel()).getServer(), before);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-request"); return null; }
    }
    public static final class Submission {
        private final net.minecraft.server.MinecraftServer server; private final CompoundTag before;
        private Submission(net.minecraft.server.MinecraftServer server, CompoundTag before) { this.server = server; this.before = before; }
        public void finish(boolean accepted, boolean threw) {
            try {
                var after = before.copy(); after.putString("Submission", threw ? "native-threw" : accepted ? "accepted-by-native-logistics" : "rejected-by-native-logistics");
                after.putBoolean("DeliveryConfirmed", false); after.putBoolean("NativeThrew", threw);
                ModStateAudit.receipt(server, "create_vibrant_vaults", "logistics-submission", ActionType.MACHINE_STATE_CHANGE, before, after);
            } catch (RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-submission-finish"); }
        }
    }
    public static Action behaviour(Object value, String operation) {
        try { return begin(StateAccess.field(value, "blockEntity"), operation, false); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) { MachineAudit.failure("vibrant-behaviour"); return null; }
    }
    public static Action handlerBulk(Object handler, String operation) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        var ref = OWNERS.get(handler); return ref == null ? null : begin(ref.get(), operation, true);
    }
    private static CompoundTag snapshot(BlockEntity be, boolean contents) throws ReflectiveOperationException {
        var tag = state(be);
        if (contents) {
            var inventory = inventory(be);
            if (inventory != null) {
                int size = inventory.getSlots(); if (size > 16384) throw new IllegalStateException("Physical inventory budget exceeded");
                var slots = new ListTag();
                for (int i = 0; i < size; i++) {
                    var slot = new CompoundTag(); slot.putInt("Slot", i);
                    slot.put("Stack", inventory.getStackInSlot(i).saveOptional(be.getLevel().registryAccess())); slots.add(slot);
                }
                tag.put("PhysicalInventory", slots); tag.putInt("Slots", size);
            }
        }
        return tag;
    }
    private static CompoundTag state(BlockEntity be) throws ReflectiveOperationException {
        var tag = MachineAudit.location(be); String kind = kind(be); tag.putString("Kind", kind);
        switch (kind) {
            case "vault" -> raw(tag, be, "controller", "radius", "length", "lastKnownPos");
            case "packager" -> {
                raw(tag, be, "redstonePowered", "signBasedAddress", "hasCustomComputerAddress", "customComputerAddress");
                if (MachineAudit.policy(be, ActionType.MACHINE_CONTENT_CHANGE)) {
                    stack(tag, be, "heldBox"); stack(tag, be, "previouslyUnwrapped");
                    var queue = (List<?>) StateAccess.field(be, "queuedExitingPackages");
                    if (queue.size() > 4096) throw new IllegalStateException("Package queue budget exceeded");
                    var entries = new ListTag();
                    for (Object entry : queue) {
                        var value = new CompoundTag(); value.put("Stack", ((ItemStack)StateAccess.field(entry, "stack")).saveOptional(be.getLevel().registryAccess()));
                        value.putInt("Count", (int)StateAccess.field(entry, "count")); entries.add(value);
                    }
                    tag.put("QueuedExitingPackages", entries);
                }
            }
            case "port" -> {
                raw(tag, be, "acceptsPackages", "addressFilter", "goggles", "passiveYaw", "currentlyDepositing");
                Object target = StateAccess.field(be, "target");
                if (target != null) tag.put("Target", codec(be, target, "com.simibubi.create.content.logistics.packagePort.PackagePortTarget"));
                if (MachineAudit.policy(be, ActionType.MACHINE_CONTENT_CHANGE)) stack(tag, be, "animatedPackage");
            }
            case "link" -> { raw(tag, be, "placedBy"); raw(tag, StateAccess.field(be, "behaviour"), "freqId", "redstonePower", "addedGlobally"); }
            case "requester" -> {
                raw(tag, be, "allowPartialRequests", "encodedTargetAdress", "lastRequestSucceeded", "redstonePowered");
                raw(tag, StateAccess.field(be, "behaviour"), "freqId");
                tag.put("EncodedRequest", codec(be, StateAccess.field(be, "encodedRequest"), "com.simibubi.create.content.logistics.stockTicker.PackageOrderWithCrafts"));
            }
            case "panel" -> {
                raw(tag, be, "restocker");
                tag.put("RestockerColor", StateAccess.encode(StateAccess.call(be, "createVibrantVaults$getRestockerColor")));
            }
            default -> throw new IllegalStateException("Unsupported physical machine");
        }
        return tag;
    }
    private static void raw(CompoundTag tag, Object source, String... fields) throws ReflectiveOperationException {
        for (String field : fields) {
            Object value = StateAccess.field(source, field);
            if (value instanceof net.minecraft.core.BlockPos pos) {
                var location = new CompoundTag(); location.putInt("X", pos.getX()); location.putInt("Y", pos.getY()); location.putInt("Z", pos.getZ()); tag.put(field, location);
            } else tag.put(field, StateAccess.encode(value));
        }
    }
    private static void stack(CompoundTag tag, BlockEntity be, String field) throws ReflectiveOperationException {
        Object value = StateAccess.field(be, field);
        tag.put(field, value instanceof ItemStack stack ? stack.saveOptional(be.getLevel().registryAccess()) : new CompoundTag());
    }
    @SuppressWarnings("unchecked")
    private static Tag codec(BlockEntity be, Object value, String type) throws ReflectiveOperationException {
        var codec = (Codec<Object>) Class.forName(type).getField("CODEC").get(null);
        return codec.encodeStart(be.getLevel().registryAccess().createSerializationContext(NbtOps.INSTANCE), value).result()
                .orElseThrow(() -> new IllegalStateException("Native value cannot be encoded"));
    }
    public static void clear() { OWNERS.clear(); }
    public static final class Action {
        private final BlockEntity be; private final ModStateAudit.Action delegate;
        private Action(BlockEntity be, ModStateAudit.Action delegate) { this.be = be; this.delegate = delegate; }
        public void finish(boolean threw) { try { delegate.finish(threw); } finally { baseline(be); } }
    }
}
