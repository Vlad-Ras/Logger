package com.roften.avilixlogger.compat.ae2;

import java.util.HashMap;
import java.util.Map;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.IActionSource;
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
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional AE2 integration. All entry points run on the server thread, after a real mutation. */
public final class Ae2Audit {
    private static final ThreadLocal<Integer> POWERED_DEPTH = ThreadLocal.withInitial(() -> 0);

    private Ae2Audit() {}

    public static void enterPowered() {
        POWERED_DEPTH.set(POWERED_DEPTH.get() + 1);
    }

    public static void leavePowered() {
        int next = POWERED_DEPTH.get() - 1;
        if (next <= 0) POWERED_DEPTH.remove();
        else POWERED_DEPTH.set(next);
    }

    public static boolean insidePowered() {
        return POWERED_DEPTH.get() > 0;
    }

    public static boolean enabled() {
        return LoggerConfig.isEnabled() && LoggerConfig.VALUES.logAe2.get();
    }

    /** The returned amount, never the simulated/requested amount, is what entered the ME network. */
    public static void transfer(AEKey key, long amount, Actionable mode, IActionSource source, boolean insert) {
        if (mode != Actionable.MODULATE || amount <= 0 || key == null || source == null || !enabled()) return;
        try {
            ServerPlayer player = source.player().filter(ServerPlayer.class::isInstance)
                    .map(ServerPlayer.class::cast).orElse(null);
            IActionHost machine = source.machine().orElse(null);
            BlockEntity machineBlock = blockEntity(machine);
            if (player == null && machineBlock == null) return; // No honest actor or position to record.

            ServerLevel level = machineBlock != null && machineBlock.getLevel() instanceof ServerLevel sl
                    ? sl : player != null && player.level() instanceof ServerLevel sl ? sl : null;
            if (level == null) return;
            BlockPos pos = machineBlock != null ? machineBlock.getBlockPos() : targetPos(player);
            String origin = machine != null ? machine.getClass().getSimpleName() : "terminal";
            LogEntry e = entry(level, pos, player, insert ? ActionType.ME_PUT : ActionType.ME_TAKE,
                    "ae2:" + origin);
            if (key instanceof AEItemKey item) {
                e.itemStackNbt = NbtSerde.writeItemStack(item.toStack(), level.registryAccess());
                e.count = (int) Math.min(Integer.MAX_VALUE, amount);
                e.extra = "resource=" + key.getId() + "; amount=" + amount;
            } else {
                e.extra = key.getDisplayName().getString() + " [" + key.getId() + "] " + amount + " mB";
            }
            LoggerRuntime.storage(level).append(e);
        } catch (Exception ignored) {
            // Audit must not prevent an AE2 transfer if a third-party key refuses to serialize.
        }
    }

    private static BlockEntity blockEntity(IActionHost machine) {
        if (machine instanceof BlockEntity be) return be;
        if (machine instanceof AEBasePart part) return part.getBlockEntity();
        return null;
    }

    private static BlockPos targetPos(ServerPlayer player) {
        if (player.containerMenu instanceof AEBaseMenu menu) {
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
            e.actorUuid = player.getUUID();
            e.actorName = player.getName().getString();
        }
        return e;
    }

    public record SlotValue(String semantic, ItemStack stack) {}
    public record Snapshot(Map<Integer, SlotValue> slots, Map<String, String> settings) {}

    /** Capture only the menu's external slots. Never mistake player inventory for ME storage. */
    public static Snapshot capture(AEBaseMenu menu) {
        if (!enabled() || !(menu.getPlayer() instanceof ServerPlayer)) return null;
        Map<Integer, SlotValue> slots = new HashMap<>();
        try {
            for (int i = 0; i < menu.slots.size(); i++) {
                Slot slot = menu.slots.get(i);
                if (slot == null || menu.isPlayerSideSlot(slot)) continue;
                SlotSemantic semantic = menu.getSlotSemantic(slot);
                if (semantic == null) continue;
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
        } catch (Exception ignored) {
            return null;
        }
    }

    public static void compare(AEBaseMenu menu, Snapshot before) {
        if (before == null || !enabled() || !(menu.getPlayer() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level)) return;
        Snapshot after = capture(menu);
        if (after == null) return;
        BlockPos pos = targetPos(player);
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
        if (!enabled() || !(menu.getPlayer() instanceof ServerPlayer player)
                || !(player.level() instanceof ServerLevel level) || ItemStack.matches(before, after)) return;
        change(level, targetPos(player), player, "ae2:" + menu.getClass().getSimpleName(),
                ActionType.ME_FILTER_CHANGE, slot, before, after);
    }

    private static void change(ServerLevel level, BlockPos pos, ServerPlayer player, String source,
                               ActionType type, int slot, ItemStack old, ItemStack now) {
        LogEntry e = entry(level, pos, player, type, source);
        e.extra = "slot " + slot + ": " + describe(old) + " → " + describe(now);
        e.itemStackNbt = NbtSerde.writeItemStack(now.isEmpty() ? old : now, level.registryAccess());
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
        e.itemStackNbt = NbtSerde.writeItemStack(stack, level.registryAccess());
        e.count = count;
        e.extra = "device inventory " + source;
        LoggerRuntime.storage(level).append(e);
    }
}
