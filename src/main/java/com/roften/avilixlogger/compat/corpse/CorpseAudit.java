package com.roften.avilixlogger.compat.corpse;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/** Captures the original corpse, all pages and player inventory around one actual action. */
public final class CorpseAudit {
    private static final String[] SECTIONS = {"Main", "Armor", "OffHand", "Additional"};
    private static volatile Api api;
    private CorpseAudit() {}

    public static void install() {
        var info = ModList.get().getModContainerById("corpse");
        if (info.isEmpty()) return;
        String version = info.get().getModInfo().getVersion().toString();
        if (!version.equals("1.21.1-1.1.21")) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Corpse audit disabled: unsupported version {}", version);
            return;
        }
        try {
            var loader = CorpseAudit.class.getClassLoader();
            Class<?> menu = Class.forName("de.maxhenkel.corpse.gui.CorpseContainerBase", false, loader);
            Method corpse = menu.getMethod("getCorpse"), editable = menu.getMethod("isEditable"), history = menu.getMethod("isHistory");
            if (!AbstractContainerMenu.class.isAssignableFrom(menu) || !Entity.class.isAssignableFrom(corpse.getReturnType())
                    || editable.getReturnType() != boolean.class || history.getReturnType() != boolean.class)
                throw new IllegalStateException("Incompatible Corpse menu contract");
            Method death = corpse.getReturnType().getMethod("getDeath");
            Class<?> data = death.getReturnType();
            Method owner = data.getMethod("getPlayerUUID"), name = data.getMethod("getPlayerName");
            if (owner.getReturnType() != UUID.class || name.getReturnType() != String.class)
                throw new IllegalStateException("Incompatible Corpse owner contract");
            Method[] lists = {data.getMethod("getMainInventory"), data.getMethod("getArmorInventory"),
                    data.getMethod("getOffHandInventory"), data.getMethod("getAdditionalItems")};
            for (Method method : lists) if (!List.class.isAssignableFrom(method.getReturnType()))
                throw new IllegalStateException("Incompatible Corpse inventory contract");
            api = new Api(menu, corpse, editable, history, death, owner, name, lists);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Corpse audit disabled: incompatible optional API", failure);
        }
    }

    public static boolean handles(AbstractContainerMenu menu) {
        Api current = api;
        return current != null && current.menu.isInstance(menu);
    }

    private static boolean enabled(ServerPlayer player) {
        return player != null && player.server.isSameThread() && CaptureTypePolicy.enabled(2)
                && LoggerConfig.isEnabled() && LoggerConfig.VALUES.logContainers.get() && !CartAuditContext.restoring();
    }

    public static Action begin(ServerPlayer player, String source) {
        Api current = api;
        if (current == null || player == null || !current.menu.isInstance(player.containerMenu) || !enabled(player)) return null;
        try {
            AbstractContainerMenu menu = player.containerMenu;
            if (!(boolean) current.editable.invoke(menu) || (boolean) current.history.invoke(menu)) return null;
            Entity body = (Entity) current.corpse.invoke(menu);
            if (body == null || body.level() != player.level() || body.isRemoved()) return null;
            Object death = current.death.invoke(body);
            var before = snapshot(current, death, player);
            var inventory = EntityContainerSerde.snapshot(player.getInventory(), player.registryAccess());
            if (inventory == null) return null;
            var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.CORPSE_CONTENT_CHANGE;
            row.actorUuid = CauseContext.actorUuid(player); row.actorName = CauseContext.actorName(player);
            EntityLogIdentity.capture(row, body);
            String owner = (String) current.name.invoke(death);
            row.entityName = "тело " + owner;
            row.dim = body.level().dimension().location().toString();
            var pos = body.blockPosition(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
            row.source = "mod:corpse:" + source; row.extra = "owner=" + owner + "; corpse=" + body.getUUID() + "; audit only";
            return new Action(player, current, body, death, before, inventory, row);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AdaptiveLogDiagnostics.adapterFailure("corpse-before"); return null;
        }
    }

    private static CompoundTag snapshot(Api access, Object death, ServerPlayer player) throws ReflectiveOperationException {
        var root = new CompoundTag(); root.putString("Provider", "corpse");
        root.putUUID("OwnerUUID", (UUID) access.owner.invoke(death)); root.putString("OwnerName", (String) access.name.invoke(death));
        int total = 0;
        for (int section = 0; section < SECTIONS.length; section++) {
            var list = (List<?>) access.lists[section].invoke(death);
            if (list == null || list.size() > 4096 || (total += list.size()) > 4096)
                throw new IllegalStateException("Corpse inventory limit exceeded");
            var tag = new CompoundTag(); tag.putInt("Size", list.size()); var items = new ListTag();
            for (int slot = 0; slot < list.size(); slot++) {
                if (!(list.get(slot) instanceof ItemStack stack)) throw new IllegalStateException("Invalid Corpse stack");
                if (stack.isEmpty()) continue;
                var item = NbtSerde.snapshotItemStack(stack, player.registryAccess());
                if (item == null) throw new IllegalStateException("Corpse item codec failed");
                item.putInt("Slot", slot); items.add(item);
            }
            tag.put("Items", items); root.put(SECTIONS[section], tag);
        }
        return root;
    }

    private record Api(Class<?> menu, Method corpse, Method editable, Method history, Method death,
                       Method owner, Method name, Method[] lists) {}

    public static final class Action {
        private final ServerPlayer player;
        private final Api access;
        private final Entity body;
        private final Object death;
        private final CompoundTag before, playerBefore;
        private final LogEntry row;
        private boolean finished;
        private Action(ServerPlayer player, Api access, Entity body, Object death, CompoundTag before,
                       CompoundTag playerBefore, LogEntry row) {
            this.player = player; this.access = access; this.body = body; this.death = death;
            this.before = before; this.playerBefore = playerBefore; this.row = row;
        }

        public void finish(boolean nativeThrew) {
            if (finished) return;
            finished = true;
            if (!enabled(player)) return;
            try {
                // Do not follow a replacement menu or replacement death object after transferItems.
                if (access.death.invoke(body) != death) return;
                var after = snapshot(access, death, player);
                var playerAfter = EntityContainerSerde.snapshot(player.getInventory(), player.registryAccess());
                if (playerAfter == null) return;
                var storage = LoggerRuntime.storage(player.serverLevel());
                var registries = player.registryAccess();
                // Only detached values enter the worker, never this Action/player/body/death.
                CompoundTag previous = before, inventoryBefore = playerBefore;
                LogEntry receipt = row;
                AsyncLogProcessor.submit(PayloadSizeEstimator.estimateTags(previous, after, inventoryBefore, playerAfter), () -> {
                    if (previous.equals(after) && inventoryBefore.equals(playerAfter)) return;
                    var changes = new ListTag();
                    var summary = new StringBuilder();
                    for (String section : SECTIONS) for (var change : ContainerSlotDiffUtil.diffSlots(
                            previous.getCompound(section), after.getCompound(section), registries)) {
                        var item = new CompoundTag(); item.putString("Section", section); item.putInt("Slot", change.slot());
                        var first = change.before().isEmpty() ? new CompoundTag() : NbtSerde.snapshotItemStack(change.before(), registries);
                        var last = change.after().isEmpty() ? new CompoundTag() : NbtSerde.snapshotItemStack(change.after(), registries);
                        if (first == null || last == null) throw new IllegalStateException("Corpse diff item codec failed");
                        item.put("Before", first); item.put("After", last);
                        if (changes.size() < 6) summary.append("; ").append(section).append('[').append(change.slot())
                                .append("]: ").append(label(change.before())).append(" -> ").append(label(change.after()));
                        changes.add(item);
                    }
                    after.put("SlotChanges", changes); after.putBoolean("NativeThrew", nativeThrew);
                    receipt.extra += "; changedSlots=" + changes.size() + summary + (nativeThrew ? "; NativeThrew" : "");
                    receipt.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, previous);
                    receipt.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, after);
                    receipt.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_BEFORE, inventoryBefore);
                    receipt.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_AFTER, playerAfter);
                    storage.append(receipt);
                });
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                AdaptiveLogDiagnostics.adapterFailure("corpse-after");
            }
        }
    }

    private static String label(ItemStack stack) {
        return stack.isEmpty() ? "empty" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }
}
