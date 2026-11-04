package com.roften.avilixlogger.compat.corpse;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;

/** Event-driven lifecycle. No corpse tick, timer or world scan is added. */
public final class CorpseLifecycleAudit {
    private static final ThreadLocal<Action> REMOVING = new ThreadLocal<>();
    private static final ThreadLocal<Action> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<Reason> REASON = new ThreadLocal<>();
    private CorpseLifecycleAudit() {}
    public static boolean handles(Entity body) { return body instanceof CorpseLifecycleAccess && CorpseAudit.handles(body); }
    private static boolean enabled(Entity body) {
        return handles(body) && body.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && LoggerConfig.isEnabled() && LoggerConfig.VALUES.logEntities.get()
                && CaptureTypePolicy.enabled(3) && !CartAuditContext.restoring();
    }
    public static void joined(Entity body, boolean loaded) {
        if (!enabled(body)) return;
        try {
            var tag = snapshot(body);
            var row = row(body, loaded ? "load" : "spawn", ActionType.ENTITY_SPAWN);
            if (loaded) { row.actorUuid = null; row.actorName = "SYSTEM"; }
            row.extra += loaded ? "; persisted body loaded; not a new death" : "; new world body";
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, tag);
            LoggerRuntime.storage((ServerLevel) body.level()).append(row);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AdaptiveLogDiagnostics.adapterFailure("corpse-join");
        }
    }
    public static Action begin(Entity body, String operation, boolean removal) {
        if (!enabled(body) || body.isRemoved() || (!removal && body.level().getEntity(body.getId()) != body)) return null;
        if (removal && REMOVING.get() != null) return null;
        for (var current = ACTIVE.get(); current != null; current = current.parent) if (current.body == body) return null;
        try {
            var action = new Action(body, snapshot(body), row(body, operation,
                    removal ? ActionType.ENTITY_DEATH : ActionType.ENTITY_EQUIPMENT_CHANGE), removal, ACTIVE.get());
            ACTIVE.set(action);
            if (removal) REMOVING.set(action);
            return action;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            AdaptiveLogDiagnostics.adapterFailure("corpse-lifecycle-before"); return null;
        }
    }
    public static ReasonScope reason(Entity body, String operation, net.minecraft.world.damagesource.DamageSource damage) {
        if (!enabled(body)) return new ReasonScope(null, null, false);
        try {
        var previous = REASON.get(); var data = new CompoundTag(); data.putString("Operation", operation);
        if (damage != null) {
            data.putString("DamageType", damage.typeHolder().unwrapKey().map(key -> key.location().toString()).orElse(damage.getMsgId()));
            if (damage.getEntity() != null) { data.putUUID("CausingEntity", damage.getEntity().getUUID()); String name = EntityLogIdentity.name(damage.getEntity()); if (name != null) data.putString("CausingName", name); }
            if (damage.getDirectEntity() != null) data.putUUID("DirectEntity", damage.getDirectEntity().getUUID());
        }
        var current = new Reason(body, data); REASON.set(current); return new ReasonScope(previous, current, true);
        } catch (RuntimeException | LinkageError failure) {
            AdaptiveLogDiagnostics.adapterFailure("corpse-removal-cause"); return new ReasonScope(null, null, false);
        }
    }
    private record Reason(Entity body, CompoundTag tag) {}
    public static final class ReasonScope implements AutoCloseable {
        private final Reason previous, current; private boolean active;
        private ReasonScope(Reason previous, Reason current, boolean active) { this.previous = previous; this.current = current; this.active = active; }
        public void close() { if (!active) return; active = false; if (REASON.get() == current) { if (previous == null) REASON.remove(); else REASON.set(previous); } }
    }
    private static CompoundTag snapshot(Entity body) throws ReflectiveOperationException {
        var tag = CorpseAudit.bodySnapshot(body);
        var nativeTag = NbtSerde.snapshotEntity((ServerLevel) body.level(), body);
        if (nativeTag == null) throw new IllegalStateException("Corpse entity codec failed");
        tag.put("Entity", nativeTag);
        for (String method : new String[]{"isSkeleton", "getCorpseModel", "getCorpseName", "getCorpseUUID"}) {
            Object value = body.getClass().getMethod(method).invoke(body);
            tag.putString(method, String.valueOf(value));
        }
        var equipment = (java.util.List<?>) body.getClass().getMethod("getEquipment").invoke(body);
        if (equipment.size() > 4096) throw new IllegalStateException("Corpse equipment limit");
        var equipmentTag = new net.minecraft.nbt.ListTag();
        for (Object value : equipment) {
            var stack = (net.minecraft.world.item.ItemStack) value;
            var item = stack.isEmpty() ? new CompoundTag() : NbtSerde.snapshotItemStack(stack, body.registryAccess());
            if (item == null) throw new IllegalStateException("Corpse equipment codec failed");
            equipmentTag.add(item);
        }
        tag.put("DisplayEquipment", equipmentTag);
        return tag;
    }
    private static LogEntry row(Entity body, String operation, ActionType type) {
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = type;
        EntityLogIdentity.capture(row, body); row.dim = body.level().dimension().location().toString();
        var pos = body.blockPosition(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
        var cause = CauseContext.peek(); row.actorUuid = cause == null ? null : cause.actorUuid();
        row.actorName = cause == null || cause.actorUuid() == null ? "SYSTEM" : cause.actorName();
        row.source = "mod:corpse:" + operation;
        row.extra = "corpse=" + body.getUUID() + "; operation=" + operation + "; audit only";
        return row;
    }
    /** Items emitted synchronously by native removal are linked to their original body. */
    public static boolean dropped(ItemEntity item) {
        var action = REMOVING.get();
        if (action == null || !enabled(action.body) || item.level() != action.body.level()) return false;
        try {
            var tag = NbtSerde.snapshotItemStack(item.getItem(), item.registryAccess());
            if (tag == null) return false;
            var row = row(item, "removal-drop", ActionType.ENTITY_SPAWN);
            row.extra = "corpse=" + action.body.getUUID() + "; deathId=" + action.before.getUUID("DeathId") + "; removal-scope item spawn";
            row.count = item.getItem().getCount(); row.deferSnapshot(LogEntry.SnapshotField.ITEM, tag);
            var link = new CompoundTag(); link.putUUID("CorpseUUID", action.body.getUUID());
            link.putUUID("DeathId", action.before.getUUID("DeathId"));
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, link);
            LoggerRuntime.storage((ServerLevel) item.level()).append(row); return true;
        } catch (RuntimeException | LinkageError failure) {
            AdaptiveLogDiagnostics.adapterFailure("corpse-drop"); return false;
        }
    }
    public static final class Action {
        private final Entity body;
        private final CompoundTag before;
        private final LogEntry row;
        private final boolean removal;
        private boolean done;
        private final Action parent;
        private Action(Entity body, CompoundTag before, LogEntry row, boolean removal, Action parent) {
            this.parent = parent;
            var reason = REASON.get(); if (reason != null && reason.body == body) before.put("Cause", reason.tag.copy());
            this.body = body; this.before = before; this.row = row; this.removal = removal;
        }
        public void finish(boolean nativeThrew) {
            if (done) return; done = true;
            if (removal && REMOVING.get() == this) REMOVING.remove();
            if (ACTIVE.get() == this) { if (parent == null) ACTIVE.remove(); else ACTIVE.set(parent); }
            if (!enabled(body)) return;
            try {
                if (removal && !body.isRemoved()) return;
                var after = snapshot(body);
                var comparable = before.copy(); comparable.remove("Cause");
                if (!removal && comparable.equals(after)) return;
                after.putBoolean("Removed", body.isRemoved()); after.putBoolean("NativeThrew", nativeThrew);
                if (body.getRemovalReason() != null) after.putString("RemovalReason", body.getRemovalReason().name());
                row.extra += "; owner=" + before.getString("OwnerName") + "; ownerUuid=" + before.getUUID("OwnerUUID")
                        + "; deathId=" + before.getUUID("DeathId") + (nativeThrew ? "; NativeThrew" : "");
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, before);
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, after);
                LoggerRuntime.storage((ServerLevel) body.level()).append(row);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                AdaptiveLogDiagnostics.adapterFailure("corpse-lifecycle-after");
            }
        }
    }
}
