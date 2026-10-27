package com.roften.avilixlogger.compat.ae2;

import java.util.HashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.IGridNode;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.util.IConfigurableObject;
import appeng.menu.AEBaseMenu;
import appeng.menu.SlotSemantic;
import appeng.parts.AEBasePart;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LoggerRuntime;
import com.roften.avilixlogger.core.NbtSerde;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional AE2 integration. All entry points run on the server thread, after a real mutation. */
public final class Ae2Audit {
    // Only the WrapMethod hooks enter this scope, and both close it in finally.
    // A primitive-only holder can remain empty without retaining game objects.
    private static final ThreadLocal<PoweredScope> POWERED_SCOPE = new ThreadLocal<>();
    private static final class PoweredScope { int depth; }

    private Ae2Audit() {}

    public static void enterPowered() {
        PoweredScope scope = POWERED_SCOPE.get();
        if (scope == null) {
            scope = new PoweredScope();
            POWERED_SCOPE.set(scope);
        }
        scope.depth++;
    }

    public static void leavePowered() {
        PoweredScope scope = POWERED_SCOPE.get();
        if (scope != null && scope.depth > 0) scope.depth--;
    }

    public static boolean insidePowered() {
        PoweredScope scope = POWERED_SCOPE.get();
        return scope != null && scope.depth > 0;
    }

    public static boolean enabled() {
        try {
            return com.roften.avilixlogger.core.CaptureTypePolicy.enabled(2)
                    && LoggerConfig.isEnabled() && LoggerConfig.VALUES.logAe2.get();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** The returned amount, never the simulated/requested amount, is what entered the ME network. */
    public static void transfer(AEKey key, long amount, Actionable mode, IActionSource source, boolean insert) {
        if (mode != Actionable.MODULATE || amount <= 0 || key == null || source == null || !enabled()) return;
        try {
            Transfer captured = captureTransfer(key, amount, mode, source, insert);
            if (captured != null) LoggerRuntime.storage(captured.level()).append(captured.row());
        } catch (Throwable failure) {
            com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-transfer");
        }
    }

    /** Native data kernel only: no append or authorization change. Production must pass enabled(). */
    public static LogEntry transferRow(AEKey key, long amount, Actionable mode, IActionSource source, boolean insert) {
        Transfer captured = captureTransfer(key, amount, mode, source, insert);
        return captured == null ? null : captured.row();
    }

    private record Transfer(ServerLevel level, LogEntry row) {}
    private static Transfer captureTransfer(AEKey key, long amount, Actionable mode, IActionSource source, boolean insert) {
        if (mode != Actionable.MODULATE || amount <= 0 || key == null || source == null) return null;
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !server.isSameThread()) return null;
        ServerPlayer player = source.player().filter(ServerPlayer.class::isInstance).map(ServerPlayer.class::cast).orElse(null);
        IActionHost machine = source.machine().orElse(null);
        Origin origin = origin(machine);
        BlockEntity machineBlock = origin == null ? null : origin.block();
        if (player == null && machineBlock == null) return null;
        ServerLevel level;
        if (machineBlock != null) level = machineBlock.getLevel() instanceof ServerLevel sl ? sl : null;
        else level = player != null && player.level() instanceof ServerLevel sl ? sl : null;
        if (level == null || level.getServer() != server) return null;
        BlockPos pos = machineBlock == null ? targetPos(player) : machineBlock.getBlockPos();
        LogEntry row = entry(level, pos, player, insert ? ActionType.ME_PUT : ActionType.ME_TAKE,
                "ae2:" + (origin == null ? "terminal" : origin.name()));
        var type = key.getType();
        String kind = key instanceof AEItemKey ? "i" : key instanceof appeng.api.stacks.AEFluidKey ? "f" : "g";
        net.minecraft.nbt.CompoundTag keyTag = null;
        if (key instanceof AEItemKey item) {
            var snapshot = NbtSerde.snapshotItemStack(item.toStack(), level.registryAccess());
            if (snapshot == null) throw new IllegalStateException("AE2 item snapshot unavailable");
            row.deferSnapshot(LogEntry.SnapshotField.ITEM, snapshot);
            row.count = (int) Math.min(Integer.MAX_VALUE, amount);
        } else {
            keyTag = key.toTagGeneric(level.registryAccess()).copy();
        }
        com.roften.avilixlogger.core.Ae2TransferDetails.attach(row, kind, key.getId().toString(), type.getId().toString(),
                amount, type.getUnitSymbol(), type.getAmountPerUnit(), keyTag);
        return new Transfer(level, row);
    }

    private record Origin(BlockEntity block, String name) {}
    private static Origin origin(IActionHost machine) {
        if (machine == null) return null;
        try {
            Object owner = machine;
            if (!(owner instanceof BlockEntity) && !(owner instanceof AEBasePart)) {
                IGridNode node = machine.getActionableNode();
                owner = node == null ? null : node.getOwner();
            }
            if (owner instanceof BlockEntity be) return new Origin(be, owner.getClass().getSimpleName());
            if (owner instanceof AEBasePart part) {
                BlockEntity be = part.getBlockEntity();
                if (be != null) return new Origin(be, owner.getClass().getSimpleName() + ":" + part.getSide());
            }
        } catch (RuntimeException | LinkageError failure) {
            com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-transfer-origin");
        }
        return new Origin(null, machine.getClass().getSimpleName());
    }

    private static BlockPos targetPos(ServerPlayer player) {
        return targetPos(player.containerMenu instanceof AEBaseMenu menu ? menu : null, player);
    }

    private static BlockPos targetPos(AEBaseMenu menu, ServerPlayer player) {
        if (menu != null) {
            Object target = menu.getTarget();
            if (target instanceof BlockEntity be) return be.getBlockPos();
            if (target instanceof AEBasePart part && part.getBlockEntity() != null)
                return part.getBlockEntity().getBlockPos();
        }
        return player.blockPosition(); // Wireless/portable terminal has no fixed world position.
    }

    private static LogEntry entry(ServerLevel level, BlockPos pos, ServerPlayer player, ActionType type, String source) {
        LogEntry e = new LogEntry();
        e.ts = System.currentTimeMillis();
        e.dim = level.dimension().location().toString();
        e.x = pos.getX();
        e.y = pos.getY();
        e.z = pos.getZ();
        e.type = type;
        e.source = source;
        if (player != null) {
            e.actorUuid = com.roften.avilixlogger.core.CauseContext.actorUuid(player);
            e.actorName = com.roften.avilixlogger.core.CauseContext.actorName(player);
        }
        return e;
    }

    public record SlotValue(String semantic, ItemStack stack) {}
    public record Snapshot(Map<Integer, SlotValue> slots, Map<String, String> settings) {}

    public static boolean onServerThread(AEBaseMenu menu) {
        return menu.getPlayer() instanceof ServerPlayer player && player.server.isSameThread();
    }

    /** Capture only the menu's external slots. Never mistake player inventory for ME storage. */
    public static Snapshot capture(AEBaseMenu menu) {
        if (!enabled() || !onServerThread(menu)) return null;
        return snapshot(menu);
    }

    /** Data-only native snapshot kernel; production capture must pass enabled() first. */
    public static Snapshot snapshot(AEBaseMenu menu) {
        if (!onServerThread(menu)) return null;
        Map<Integer, SlotValue> slots = new HashMap<>();
        try {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot == null || slot.container instanceof Inventory) continue;
                SlotSemantic semantic = menu.getSlotSemantic(slot);
                if (semantic == null || semantic.playerSide()) continue;
                String id = semantic.id();
                // Output/crafting slots can change while the menu is open without a player action.
                if (!id.equals("UPGRADE") && !id.equals("CONFIG") && !id.equals("VIEW_CELL")
                        && !id.equals("STORAGE_CELL") && !id.equals("STORAGE") && !id.equals("MACHINE_INPUT")
                        && !id.equals("MACHINE_OUTPUT") && !id.equals("BLANK_PATTERN")
                        && !id.equals("ENCODED_PATTERN") && !id.equals("PROCESSING_INPUTS")
                        && !id.equals("PROCESSING_OUTPUTS")) continue;
                slots.put(i, new SlotValue(id, slot.getItem().copy()));
            }
            Map<String, String> settings = menu.getTarget() instanceof IConfigurableObject configurable
                    ? new HashMap<>(configurable.getConfigManager().exportSettings()) : Map.of();
            return new Snapshot(slots, settings);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void compare(AEBaseMenu menu, Snapshot before) {
        if (before == null || !enabled() || !onServerThread(menu) || !(menu.getPlayer() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) return;
        Snapshot after = capture(menu);
        if (after == null) return;
        BlockPos pos = targetPos(menu, player);
        String source = "ae2:" + menu.getClass().getSimpleName();
        for (var previous : before.slots().entrySet()) {
            int slot = previous.getKey();
            SlotValue old = previous.getValue();
            SlotValue now = after.slots().get(slot);
            if (now == null || ItemStack.matches(old.stack(), now.stack())) continue;
            String id = old.semantic();
            if (id.equals("CONFIG") || id.equals("PROCESSING_INPUTS") || id.equals("PROCESSING_OUTPUTS")) {
                change(level, pos, player, source, ActionType.ME_FILTER_CHANGE, slot, old.stack(), now.stack());
            } else if (id.equals("UPGRADE")) {
                change(level, pos, player, source, ActionType.ME_UPGRADE_CHANGE, slot, old.stack(), now.stack());
            } else if (id.equals("VIEW_CELL") || id.equals("STORAGE_CELL")) {
                change(level, pos, player, source, ActionType.ME_CELL_CHANGE, slot, old.stack(), now.stack());
            } else {
                physicalChange(level, pos, player, source + ":" + id + ":slot=" + slot, old.stack(), now.stack());
            }
        }
        for (var setting : before.settings().entrySet()) {
            String newValue = after.settings().get(setting.getKey());
            if (newValue == null || setting.getValue().equals(newValue)) continue;
            LogEntry e = entry(level, pos, player, ActionType.ME_SETTING_CHANGE, source);
            e.extra = setting.getKey() + ": " + setting.getValue() + " → " + newValue;
            LoggerRuntime.storage(level).append(e);
        }
    }

    public static void stockAmount(AEBaseMenu menu, int slot, ItemStack before, ItemStack after) {
        if (!enabled() || !onServerThread(menu) || !(menu.getPlayer() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level) || ItemStack.matches(before, after)) return;
        change(level, targetPos(menu, player), player, "ae2:" + menu.getClass().getSimpleName(),
                ActionType.ME_FILTER_CHANGE, slot, before, after);
    }

    private static void change(ServerLevel level, BlockPos pos, ServerPlayer player, String source,
                               ActionType type, int slot, ItemStack old, ItemStack now) {
        LogEntry e = entry(level, pos, player, type, source);
        e.extra = "slot " + slot + ": " + describe(old) + " → " + describe(now);
        e.deferSnapshot(LogEntry.SnapshotField.ITEM, NbtSerde.snapshotItemStack(now.isEmpty() ? old : now, level.registryAccess()));
        LoggerRuntime.storage(level).append(e);
    }

    private static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "пусто";
        GenericStack wrapped = GenericStack.fromItemStack(stack);
        if (wrapped != null) return wrapped.what().getDisplayName().getString() + " ["
                + wrapped.what().getId() + "] x" + wrapped.amount();
        return stack.getHoverName().getString() + " x" + stack.getCount();
    }

    private static void physicalChange(ServerLevel level, BlockPos pos, ServerPlayer player, String source,
                                       ItemStack old, ItemStack now) {
        if (ItemStack.isSameItemSameComponents(old, now)) {
            int delta = now.getCount() - old.getCount();
            if (delta != 0) physicalRow(level, pos, player, source,
                    delta > 0 ? ActionType.ME_PUT : ActionType.ME_TAKE, delta > 0 ? now : old, Math.abs(delta));
        } else {
            if (!old.isEmpty()) physicalRow(level, pos, player, source, ActionType.ME_TAKE, old, old.getCount());
            if (!now.isEmpty()) physicalRow(level, pos, player, source, ActionType.ME_PUT, now, now.getCount());
        }
    }

    private static void physicalRow(ServerLevel level, BlockPos pos, ServerPlayer player,
                                    String source, ActionType type, ItemStack stack, int count) {
        LogEntry e = entry(level, pos, player, type, source);
        e.deferSnapshot(LogEntry.SnapshotField.ITEM, NbtSerde.snapshotItemStack(stack, level.registryAccess()));
        e.count = count;
        e.extra = "device inventory " + source;
        LoggerRuntime.storage(level).append(e);
    }
}
