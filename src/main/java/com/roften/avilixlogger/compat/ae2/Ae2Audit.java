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
                        && !id.equals("INSCRIBER_PLATE_TOP") && !id.equals("INSCRIBER_PLATE_BOTTOM")
                        && !id.equals("ENCODED_PATTERN") && !id.equals("PROCESSING_INPUTS")
                        && !id.equals("PROCESSING_OUTPUTS")) continue;
                slots.put(i, new SlotValue(id, slot.getItem().copy()));
            }
            Object target = menu.getTarget();
            Map<String, String> settings = target instanceof IConfigurableObject configurable
                    ? new HashMap<>(configurable.getConfigManager().exportSettings()) : new HashMap<>();
            if (menu instanceof appeng.menu.implementations.PriorityMenu && target instanceof appeng.helpers.IPriorityHost host)
                settings.put("avilixlogger:priority", Integer.toString(host.getPriority()));
            if (menu instanceof appeng.menu.implementations.StorageLevelEmitterMenu && target instanceof appeng.parts.automation.StorageLevelEmitterPart emitter)
                settings.put("avilixlogger:reporting_value", Long.toString(emitter.getReportingValue()));
            if (menu instanceof appeng.menu.me.items.PatternEncodingTermMenu && target instanceof appeng.helpers.IPatternTerminalMenuHost host) {
                var logic = host.getLogic();
                settings.put("avilixlogger:pattern_mode", logic.getMode().name());
                settings.put("avilixlogger:pattern_substitution", Boolean.toString(logic.isSubstitution()));
                settings.put("avilixlogger:pattern_fluid_substitution", Boolean.toString(logic.isFluidSubstitution()));
                var recipe = logic.getStonecuttingRecipeId();
                if (recipe != null) settings.put("avilixlogger:stonecutting_recipe", recipe.toString());
            }
            return new Snapshot(slots, settings);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void compare(AEBaseMenu menu, Snapshot before) {
        if (before == null || !enabled() || !onServerThread(menu)) return;
        Snapshot after = capture(menu);
        if (after == null) return;
        ServerPlayer player = (ServerPlayer) menu.getPlayer();
        MenuTarget target = menuTarget(menu, player);
        if (target != null) visitChanges(target, player, "ae2:" + menu.getClass().getSimpleName(), before, after, null);
    }

    /** Native data kernel; it never appends or enables production capture. */
    public static java.util.List<LogEntry> changes(AEBaseMenu menu, Snapshot before, Snapshot after) {
        if (before == null || after == null || !onServerThread(menu)) return java.util.List.of();
        ServerPlayer player = (ServerPlayer) menu.getPlayer();
        MenuTarget target = menuTarget(menu, player);
        if (target == null) return java.util.List.of();
        var rows = new java.util.ArrayList<LogEntry>();
        visitChanges(target, player, "ae2:" + menu.getClass().getSimpleName(), before, after, rows);
        return java.util.List.copyOf(rows);
    }

    private record MenuTarget(ServerLevel level, BlockPos pos) {}
    private static MenuTarget menuTarget(AEBaseMenu menu, ServerPlayer player) {
        Object host = menu.getTarget();
        BlockEntity be = host instanceof BlockEntity block ? block : host instanceof AEBasePart part ? part.getBlockEntity() : null;
        ServerLevel level = be == null ? player.level() instanceof ServerLevel sl ? sl : null
                : be.getLevel() instanceof ServerLevel sl ? sl : null;
        if (level == null || !level.getServer().isSameThread()) return null;
        return new MenuTarget(level, be == null ? player.blockPosition() : be.getBlockPos());
    }

    /** Production emits each detached row immediately, before serializing the next slot's pair. */
    private static void visitChanges(MenuTarget target, ServerPlayer player, String source, Snapshot before, Snapshot after, java.util.List<LogEntry> collector) {
        for (var previous : before.slots().entrySet()) {
            int slot = previous.getKey(); SlotValue old = previous.getValue(); SlotValue now = after.slots().get(slot);
            // A changed menu layout is not evidence that an absent slot became empty.
            if (now == null || !old.semantic().equals(now.semantic()) || ItemStack.matches(old.stack(), now.stack())) continue;
            ActionType type = configurationType(old.semantic());
            if (type != null) {
                LogEntry row = configurationRow(target, player, source, type, old.semantic(), slot, old.stack(), now.stack());
                if (row != null) emit(target, row, collector);
            } else physicalChange(target, player, source + ":" + old.semantic() + ":slot=" + slot, old.stack(), now.stack(), collector);
        }
        var settings = new java.util.LinkedHashSet<>(before.settings().keySet()); settings.addAll(after.settings().keySet());
        for (String name : settings) {
            String old = before.settings().get(name), now = after.settings().get(name);
            if (java.util.Objects.equals(old, now)) continue;
            LogEntry row = entry(target.level(), target.pos(), player, ActionType.ME_SETTING_CHANGE, source);
            com.roften.avilixlogger.core.Ae2ConfigurationDetails.setting(row, name, old, now); emit(target, row, collector);
        }
    }
    private static void emit(MenuTarget target, LogEntry row, java.util.List<LogEntry> collector) {
        if (row == null) return;
        if (collector == null) LoggerRuntime.storage(target.level()).append(row);
        else collector.add(row);
    }

    public static void stockAmount(AEBaseMenu menu, int slot, ItemStack before, ItemStack after) {
        if (!enabled() || !onServerThread(menu)) return;
        ServerPlayer player = (ServerPlayer) menu.getPlayer(); MenuTarget target = menuTarget(menu, player);
        if (target == null || ItemStack.matches(before, after)) return;
        LogEntry row = configurationRow(target, player, "ae2:" + menu.getClass().getSimpleName(), ActionType.ME_FILTER_CHANGE, "CONFIG", slot, before, after);
        if (row != null) LoggerRuntime.storage(target.level()).append(row);
    }

    /** Data-only slot kernel for stock submenus and native fixtures. */
    public static LogEntry configurationRow(AEBaseMenu menu, String semantic, int slot, ItemStack before, ItemStack after) {
        if (!onServerThread(menu) || before == null || after == null || ItemStack.matches(before, after)) return null;
        ActionType type = configurationType(semantic); if (type == null) return null;
        ServerPlayer player = (ServerPlayer) menu.getPlayer(); MenuTarget target = menuTarget(menu, player);
        return target == null ? null : configurationRow(target, player, "ae2:" + menu.getClass().getSimpleName(), type, semantic, slot, before, after);
    }
    private static ActionType configurationType(String semantic) {
        return com.roften.avilixlogger.core.Ae2ConfigurationDetails.typeForSemantic(semantic);
    }
    private static LogEntry configurationRow(MenuTarget target, ServerPlayer player, String source, ActionType type,
                                              String semantic, int slot, ItemStack before, ItemStack after) {
        var old = before.isEmpty() ? null : NbtSerde.snapshotItemStack(before, target.level().registryAccess());
        var now = after.isEmpty() ? null : NbtSerde.snapshotItemStack(after, target.level().registryAccess());
        if ((!before.isEmpty() && old == null) || (!after.isEmpty() && now == null)) {
            com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-config-snapshot"); return null;
        }
        LogEntry row = entry(target.level(), target.pos(), player, type, source);
        com.roften.avilixlogger.core.Ae2ConfigurationDetails.slot(row, semantic, slot, resource(before), resource(after), old, now);
        return row;
    }
    private static com.roften.avilixlogger.core.Ae2ConfigurationDetails.Resource resource(ItemStack stack) {
        if (stack.isEmpty()) return new com.roften.avilixlogger.core.Ae2ConfigurationDetails.Resource("empty", "", 0);
        GenericStack wrapped = GenericStack.fromItemStack(stack);
        if (wrapped != null) return new com.roften.avilixlogger.core.Ae2ConfigurationDetails.Resource(
                wrapped.what() instanceof AEItemKey ? "i" : wrapped.what() instanceof appeng.api.stacks.AEFluidKey ? "f" : "g", wrapped.what().getId().toString(), wrapped.amount());
        return new com.roften.avilixlogger.core.Ae2ConfigurationDetails.Resource("i", net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount());
    }

    private static void physicalChange(MenuTarget target, ServerPlayer player, String source, ItemStack old, ItemStack now, java.util.List<LogEntry> collector) {
        if (ItemStack.isSameItemSameComponents(old, now)) {
            int delta = now.getCount() - old.getCount();
            if (delta != 0) emit(target, physicalRow(target.level(), target.pos(), player, source,
                    delta > 0 ? ActionType.ME_PUT : ActionType.ME_TAKE, delta > 0 ? now : old, Math.abs(delta)), collector);
        } else {
            if (!old.isEmpty()) emit(target, physicalRow(target.level(), target.pos(), player, source, ActionType.ME_TAKE, old, old.getCount()), collector);
            if (!now.isEmpty()) emit(target, physicalRow(target.level(), target.pos(), player, source, ActionType.ME_PUT, now, now.getCount()), collector);
        }
    }
    private static LogEntry physicalRow(ServerLevel level, BlockPos pos, ServerPlayer player,
                                        String source, ActionType type, ItemStack stack, int count) {
        LogEntry row = entry(level, pos, player, type, source);
        var item = NbtSerde.snapshotItemStack(stack, level.registryAccess());
        if (item == null) {
            com.roften.avilixlogger.core.AdaptiveLogDiagnostics.adapterFailure("ae2-device-snapshot"); return null;
        }
        com.roften.avilixlogger.core.Ae2TransferDetails.device(row,
                net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), count, item);
        row.count = count; return row;
    }
}
