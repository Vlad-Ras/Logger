package com.roften.avilixlogger.compat.accessories;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Optional public callback; no capability lookup, added tick listener or mandatory dependency. */
public final class AccessoriesAudit {
    private AccessoriesAudit() {}

    public static void install() {
        var info = ModList.get().getModContainerById("accessories");
        if (info.isEmpty()) return;
        String version = info.get().getModInfo().getVersion().toString();
        if (!version.equals("1.1.0-beta.54+1.21.1")) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Accessories audit disabled: unsupported version {}", version);
            return;
        }
        try {
            var loader = AccessoriesAudit.class.getClassLoader();
            Class<?> callback = Class.forName("io.wispforest.accessories.api.events.AccessoryChangeCallback", false, loader);
            Class<?> reference = Class.forName("io.wispforest.accessories.api.slot.SlotReference", false, loader);
            Class<?> state = Class.forName("io.wispforest.accessories.api.events.SlotStateChange", false, loader);
            Class<?> event = Class.forName("net.fabricmc.fabric.api.event.Event", false, loader);
            Method change = callback.getMethod("onChange", ItemStack.class, ItemStack.class, reference, state);
            Method entity = reference.getMethod("entity"), slot = reference.getMethod("slot"),
                    slotName = reference.getMethod("slotName"), path = reference.getMethod("createSlotPath");
            if (!callback.isInterface() || change.getReturnType() != void.class || !state.isEnum()
                    || entity.getReturnType() != LivingEntity.class || slot.getReturnType() != int.class
                    || slotName.getReturnType() != String.class || path.getReturnType() != String.class)
                throw new IllegalStateException("Incompatible Accessories callback contract");
            Object listener = Proxy.newProxyInstance(loader, new Class<?>[]{callback}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                    case "toString" -> "AvilixLogger Accessories observer";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                };
                if (method.equals(change) && enabled()) {
                    try {
                        Object target = entity.invoke(args[2]);
                        if (target instanceof LivingEntity wearer && wearer.level() instanceof ServerLevel level
                                && level.getServer().isSameThread()) {
                            capture(level, wearer, (ItemStack) args[0], (ItemStack) args[1],
                                    (String) slotName.invoke(args[2]), (int) slot.invoke(args[2]),
                                    (String) path.invoke(args[2]), ((Enum<?>) args[3]).name());
                        }
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                        AdaptiveLogDiagnostics.adapterFailure("accessories-change");
                    }
                }
                return null;
            });
            // Resolve every method before mutating the upstream callback list.
            Method register = event.getMethod("register", Object.class);
            Object upstreamEvent = callback.getField("EVENT").get(null);
            if (!event.isInstance(upstreamEvent)) throw new IllegalStateException("Incompatible event provider");
            register.invoke(upstreamEvent, listener);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Accessories audit disabled: incompatible optional API", failure);
        }
    }

    private static boolean enabled() {
        return CaptureTypePolicy.enabled(3) && LoggerConfig.isEnabled()
                && LoggerConfig.VALUES.logEntities.get() && !CartAuditContext.restoring();
    }

    private static void capture(ServerLevel level, LivingEntity wearer, ItemStack before, ItemStack after,
                                String slotName, int slot, String path, String state) {
        if (before == null || after == null || ItemStack.matches(before, after)) return;
        var first = snapshot(before, level, slotName, slot, path);
        var last = snapshot(after, level, slotName, slot, path);
        if (!enabled()) return;
        var row = new LogEntry();
        row.ts = System.currentTimeMillis(); row.type = ActionType.ENTITY_EQUIPMENT_CHANGE;
        // The callback observes a later entity tick: the wearer is not evidence of the editor.
        row.actorName = "SYSTEM";
        EntityLogIdentity.capture(row, wearer);
        row.dim = level.dimension().location().toString();
        var pos = wearer.blockPosition(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
        row.source = "mod:accessories:equipment";
        row.extra = "provider=accessories; slot=" + path + "; change=" + state + "; observation=change_callback";
        row.extra += "; before=" + label(before) + "; after=" + label(after);
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, first);
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, last);
        LoggerRuntime.storage(level).append(row);
    }

    private static CompoundTag snapshot(ItemStack stack, ServerLevel level, String name, int slot, String path) {
        var root = new CompoundTag(); root.putString("Kind", "equipment"); root.putString("Provider", "accessories");
        root.putString("SlotType", name); root.putInt("Slot", slot); root.putString("SlotPath", path);
        var item = stack.isEmpty() ? new CompoundTag() : NbtSerde.snapshotItemStack(stack, level.registryAccess());
        if (item == null) throw new IllegalStateException("Accessories item codec failed");
        root.put("Item", item); return root;
    }

    private static String label(ItemStack stack) {
        return stack.isEmpty() ? "empty" : net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()) + " x" + stack.getCount();
    }
}
