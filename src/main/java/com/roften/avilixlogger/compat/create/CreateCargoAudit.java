package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Sparse per-cell changes. Capture returns detached data; publishing retains the AuthCore gate. */
public final class CreateCargoAudit {
    public static boolean restoreLocked(AbstractContraptionEntity entity){
        return CartAuditContext.rollbackActive&&!CartAuditContext.restoring()
                &&(CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID())||CartRestoreLocks.constructionLocked(CreateContraptionSources.id(entity)));
    }
    private static final ThreadLocal<Capture> CAPTURE = new ThreadLocal<>();
    private static final ThreadLocal<OperationScope> OPERATION = new ThreadLocal<>();
    private CreateCargoAudit() {}
    private static Capture captureFor(AbstractContraptionEntity entity){for(var capture=CAPTURE.get();capture!=null;capture=capture.previous)if(capture.entity==entity)return capture;return null;}
    public static boolean active(AbstractContraptionEntity entity) {
        var capture = captureFor(entity);
        return entity.getContraption() != null && CreateContraptionSources.kind(entity) != CreateContraptionKind.UNKNOWN
                && entity.level() instanceof ServerLevel level && level.getServer().isSameThread()
                && !CartAuditContext.restoring() && !CreateStructuralAudit.covered(entity)
                && (capture != null || CartAutomationAudit.capturingChanges() || CreateCartAudit.enabled());
    }
    public static boolean internal(AbstractContraptionEntity entity) {
        var operation = OPERATION.get();
        return operation != null && operation.entity == entity && operation.internal
                || CartAuditContext.collectingLoot(CreateContraptionSources.id(entity));
    }
    public static Capture captureChanges(AbstractContraptionEntity entity) {
        if (!(entity.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("Cargo capture must run on the server thread");
        return new Capture(entity);
    }
    static boolean capture(AbstractContraptionEntity entity, CompoundTag before, CompoundTag after) {
        return capture(entity, ActionType.CART_CONTENT_CHANGE, "cargo", before, after, "cell change");
    }
    static boolean capture(AbstractContraptionEntity entity, ActionType type, String phase,
                           CompoundTag before, CompoundTag after, String detail) {
        var capture = captureFor(entity);
        if (capture == null) return false;
        if (capture.rows.size() == 512) { capture.complete = false; return true; }
        capture.rows.add(row(entity, type, phase, before, after, detail));
        return true;
    }
    public static LogEntry row(AbstractContraptionEntity entity, ActionType type, String phase,
                               CompoundTag before, CompoundTag after, String detail) {
        var kind = CreateContraptionSources.kind(entity); var id = CreateContraptionSources.id(entity);
        if (kind == CreateContraptionKind.UNKNOWN) throw new IllegalArgumentException("Unknown construction");
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = type;
        row.dim = entity.level().dimension().location().toString();
        row.x = entity.blockPosition().getX(); row.y = entity.blockPosition().getY(); row.z = entity.blockPosition().getZ();
        row.entityUuid = entity.getUUID(); row.entityType = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        var cart = CreateCartAudit.state(entity);
        row.actorUuid = null; row.actorName = "SYSTEM";
        var cause = CauseContext.peek();
        if (cause != null && cause.actorUuid() != null) { row.actorUuid = cause.actorUuid(); row.actorName = cause.actorName(); }
        row.source = CreateMechanismContext.decorate(cart != null ? cart.stamp(phase).source() : kind.source(id, phase));
        row.extra = "construction=" + id + "; " + detail;
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, metadata(entity, before));
        row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, metadata(entity, after));
        LogIdGenerator.ensure(row); return row;
    }
    static CompoundTag metadata(AbstractContraptionEntity entity, CompoundTag value) {
        if (value == null) return null;
        var tag = value.copy(); tag.putUUID("Construction", CreateContraptionSources.id(entity));
        tag.putString("ConstructionKind", CreateContraptionSources.kind(entity).name());
        tag.putUUID("StorageEntity", entity.getUUID());tag.putString("Dimension",entity.level().dimension().location().toString());
        if(entity instanceof com.simibubi.create.content.trains.entity.CarriageContraptionEntity carriage
                && carriage.getCarriage() instanceof ContraptionIdentity identity) tag.putUUID("Carriage",identity.avilixlogger$identity());
        var operation = OPERATION.get();
        if (operation != null && operation.entity == entity) {
            tag.putString(tag.hasUUID("Operation")?"MechanismOperation":"Operation", operation.reason);
            // Hand/world endpoints are not yet jointly restorable, even for an internal actor.
            if (!operation.internal) tag.putBoolean("External", true);
        }
        return tag;
    }
    public static void emit(AbstractContraptionEntity entity, ActionType type, String phase,
                            CompoundTag before, CompoundTag after, String detail) {
        if (!CreateCartAudit.enabled() || !(entity.level() instanceof ServerLevel level)) return;
        LoggerRuntime.storage(level).append(row(entity, type, phase, before, after, detail));
    }
    static void deliver(AbstractContraptionEntity entity,LogEntry row){
        var capture=captureFor(entity);
        if(capture!=null){if(capture.rows.size()<512)capture.rows.add(row);else capture.complete=false;return;}
        if(CreateCartAudit.enabled())LoggerRuntime.storage((ServerLevel)entity.level()).append(row);
    }
    /** Only native, verified block-breaking collection passes internal=true. */
    public static OperationScope operation(MovementContext context, String reason, boolean internal) {
        var entity = context.contraption.entity;
        if (entity == null || !active(entity)) return null;
        return new OperationScope(context, reason, internal);
    }
    public static final class Capture implements AutoCloseable {
        private final AbstractContraptionEntity entity;
        private final Capture previous;
        private final List<LogEntry> rows = new ArrayList<>();
        private boolean complete = true, closed;
        private Capture(AbstractContraptionEntity entity) { this.entity = entity; previous = CAPTURE.get(); CAPTURE.set(this); }
        public List<LogEntry> rows() { return List.copyOf(rows); }
        public boolean complete() { return complete; }
        @Override public void close() { if (!closed) { closed = true; if (previous == null) CAPTURE.remove(); else CAPTURE.set(previous); } }
    }
    public static final class OperationScope implements AutoCloseable {
        private final AbstractContraptionEntity entity;
        private final String reason;
        private final boolean internal;
        private final UUID owner;
        private final String ownerName;
        private final OperationScope previous;
        private final String previousSource;
        private final CreateMechanismContext.Stamp previousMechanism;
        private final CreateActorContext.Stamp previousActor;
        private final CauseContext.Scope cause;
        private boolean closed;
        private OperationScope(MovementContext context, String reason, boolean internal) {
            entity = context.contraption.entity; this.reason = reason; this.internal = internal;
            var cart = CreateCartAudit.state(entity);
            owner = context.blockEntityData != null && context.blockEntityData.hasUUID("Owner")
                    ? context.blockEntityData.getUUID("Owner") : cart == null ? null : cart.owner;
            var server = ((ServerLevel)entity.level()).getServer(); var online = owner == null ? null : server.getPlayerList().getPlayer(owner);
            var cached = owner == null ? null : net.neoforged.neoforge.common.UsernameCache.getLastKnownUsername(owner);
            ownerName = online != null ? online.getGameProfile().getName() : cart != null && owner != null && owner.equals(cart.owner)
                    && cart.ownerName != null && !cart.ownerName.isBlank() ? cart.ownerName
                    : cached != null && !cached.isBlank() ? cached : owner == null ? null : owner.toString();
            previous = OPERATION.get(); previousActor = CreateActorContext.current(); previousMechanism = CreateMechanismContext.current();
            previousSource = MutationSourceResolver.enter(CreateContraptionSources.kind(entity).source(CreateContraptionSources.id(entity), reason));
            CauseContext.Scope installedCause = null;
            try {
                CreateMechanismAudit.enter(context);
                var technical = context.temporaryData instanceof Player p ? p : null;
                CreateActorContext.enter(new CreateActorContext.Stamp(owner, ownerName, technical));
                OPERATION.set(this);
                installedCause = CauseContext.unattributed();
            } catch (Throwable failure) {
                if (installedCause != null) installedCause.close();
                restore(); throw failure;
            }
            cause = installedCause;
        }
        private void restore() {
            if (previous == null) OPERATION.remove(); else OPERATION.set(previous);
            CreateActorContext.restore(previousActor); CreateMechanismContext.restore(previousMechanism);
            MutationSourceResolver.restore(previousSource);
        }
        @Override public void close() { if (!closed) { closed = true; if (cause != null) cause.close(); restore(); } }
    }
    public static void clear() { CAPTURE.remove(); OPERATION.remove(); }
}
