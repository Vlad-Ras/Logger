package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

/** Captures actual activations, not idle ticks. Produces detached data before queueing it. */
public final class CreateDeployerAudit {
    private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
    private CreateDeployerAudit() {}
    public static Session begin(MovementContext context, BlockPos target, DeployerFakePlayer player, String mode) {
        if (context.contraption == null || !CreateCapturePolicy.actors(context.contraption.entity)
                || !CreateCartAudit.enabled()) return null;
        return capture(context, target, player, mode);
    }
    /** Capture/finish are also usable by dev fixtures; publishing always requires authorization. */
    public static Session capture(MovementContext context, BlockPos target, DeployerFakePlayer player, String mode) {
        if (!(context.world instanceof ServerLevel level) || context.contraption.entity == null
                || CreateContraptionSources.kind(context.contraption.entity) == CreateContraptionKind.UNKNOWN) return null;
        return new Session(context, target, player, mode, level);
    }
    public static boolean capturing() { return CURRENT.get() != null; }
    public static void used(String result, boolean consumed, Entity entity) {
        var session = CURRENT.get();
        if (session == null || !consumed) return;
        session.result = result;
        if (entity != null) {
            session.entityId = entity.getUUID(); session.entityType = EntityType.getKey(entity.getType()).toString();
            session.entityName = EntityLogIdentity.name(entity);
        }
    }
    public static void attacked(Entity entity, float healthBefore, float absorptionBefore, Vec3 motionBefore, boolean aliveBefore) {
        boolean changed = entity.isRemoved() || entity.isAlive() != aliveBefore || !entity.getDeltaMovement().equals(motionBefore);
        float damage = 0;
        if (entity instanceof LivingEntity living) {
            changed |= living.getHealth() != healthBefore || living.getAbsorptionAmount() != absorptionBefore;
            damage = Math.max(0, healthBefore - living.getHealth()) + Math.max(0, absorptionBefore - living.getAbsorptionAmount());
        }
        used("attack", changed, entity);
        var session = CURRENT.get(); if (session != null && changed) session.damage += damage;
    }
    public static void emit(Session session, boolean completed) {
        if (session == null || !CreateCartAudit.enabled()) return;
        var row = session.finish(completed);
        if (row == null) return;
        boolean enabled = switch (session.result == null ? "world_or_item_change" : session.result) {
            case "attack" -> LoggerConfig.VALUES.logEntityAttacks.get();
            case "entity_use", "player_feed" -> LoggerConfig.VALUES.logEntities.get();
            case "item_use", "item_first" -> LoggerConfig.VALUES.logItemUse.get();
            default -> LoggerConfig.VALUES.logGenericBlockUse.get() || LoggerConfig.VALUES.logItemUse.get();
        };
        // A disabled display category must not erase a barrier needed by period rollback.
        if (enabled||session.incomplete) LoggerRuntime.storage(session.level).append(row);
    }
    /** An incomplete history barrier prevents a later undo from crossing a failed capture. */
    public static void captureFailed(MovementContext context, BlockPos pos, String mode) {
        if (context.contraption == null || !CreateCapturePolicy.actors(context.contraption.entity)
                || !CreateCartAudit.enabled() || !(context.world instanceof ServerLevel level)) return;
        var kind = CreateContraptionSources.kind(context.contraption.entity);
        if (kind == CreateContraptionKind.UNKNOWN) return;
        var tag = new CompoundTag(); tag.putInt("Format", 1); tag.putString("Kind", "deployer_action");
        tag.putString("Mode", mode); tag.putBoolean("Complete", false); tag.putString("JointFailure", "Не удалось полностью снять действие активатора; откат запрещён");
        var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.type = ActionType.CREATE_DEPLOYER_ACTION;
        row.dim = level.dimension().location().toString(); row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
        row.source = kind.source(CreateContraptionSources.id(context.contraption.entity), "deployer"); row.actorName = "SYSTEM";
        row.extra = "create_deployer; capture_failed; complete=false";
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE, tag); row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER, tag.copy());
        LogIdGenerator.ensure(row); LoggerRuntime.storage(level).append(row);
    }
    public static final class Session implements AutoCloseable {
        private final ServerLevel level;
        private final BlockPos target;
        private final DeployerFakePlayer player;
        private final String mode;
        private final ItemStack heldBefore;
        private final BlockState blockBefore;
        private final UUID owner;
        private final String ownerName, ownerSource, source;
        private final Session previous;
        private final CreateActorContext.Stamp previousActor;
        private final CreateMechanismContext.Stamp previousMechanism;
        private final String previousSource;
        private final CauseContext.Scope cause;
        private UUID entityId;
        private String entityType, entityName, result;
        private float damage;
        private boolean incomplete;
        private boolean closed;
        private CreateDeployerTransaction.Capture transaction;
        private Session(MovementContext context, BlockPos target, DeployerFakePlayer player, String mode, ServerLevel level) {
            this.level = level; this.target = target.immutable(); this.player = player; this.mode = mode;
            heldBefore = player.getMainHandItem().copy(); blockBefore = level.getBlockState(target);
            var cart = CreateCartAudit.state(context.contraption.entity);
            if (context.blockEntityData != null && context.blockEntityData.hasUUID("Owner")) {
                owner = context.blockEntityData.getUUID("Owner"); ownerSource = "deployer";
            } else { owner = cart == null ? null : cart.owner; ownerSource = owner == null ? "unknown" : "cart"; }
            var online = owner == null ? null : level.getServer().getPlayerList().getPlayer(owner);
            var cachedName = owner == null ? null : net.neoforged.neoforge.common.UsernameCache.getLastKnownUsername(owner);
            ownerName = online != null ? online.getGameProfile().getName()
                    : cart != null && owner != null && owner.equals(cart.owner) && cart.ownerName != null && !cart.ownerName.isBlank()
                    ? cart.ownerName : cachedName != null && !cachedName.isBlank() ? cachedName : owner == null ? null : owner.toString();
            var rawSource = CreateContraptionSources.kind(context.contraption.entity)
                    .source(CreateContraptionSources.id(context.contraption.entity), "deployer");
            previousSource = MutationSourceResolver.enter(rawSource);
            previousMechanism = CreateMechanismContext.current(); previousActor = CreateActorContext.current(); previous = CURRENT.get();
            CauseContext.Scope installedCause = null; String decoratedSource;
            try {
                CreateMechanismAudit.enter(context); decoratedSource = CreateMechanismContext.decorate(rawSource);
                CreateActorContext.enter(new CreateActorContext.Stamp(owner, ownerName, player)); CURRENT.set(this);
                installedCause = CauseContext.push(player, mode.equals("PUNCH") ? CauseContext.Kind.ATTACK_ENTITY : CauseContext.Kind.USE_BLOCK, target, heldBefore);
            } catch (Throwable failure) {
                if (installedCause != null) installedCause.close();
                if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
                CreateActorContext.restore(previousActor); CreateMechanismContext.restore(previousMechanism); MutationSourceResolver.restore(previousSource);
                throw failure;
            }
            source = decoratedSource; cause = installedCause;
            try{
                transaction=CreateDeployerTransaction.begin(context,player,target);

            }catch(Throwable failure){close();throw failure;}
        }
        public LogEntry finish(boolean completed) {
            var heldAfter = player.getMainHandItem().copy(); var blockAfter = level.getBlockState(target);
            if (result == null && ItemStack.matches(heldBefore, heldAfter) && blockBefore == blockAfter && !transaction.changed()) return null;
            var row = new LogEntry(); row.ts = System.currentTimeMillis(); row.dim = level.dimension().location().toString();
            row.type = ActionType.CREATE_DEPLOYER_ACTION; row.source = source; row.actorUuid = null; row.actorName = "SYSTEM";
            row.x = target.getX(); row.y = target.getY(); row.z = target.getZ();
            row.entityUuid = entityId; row.entityType = entityType;
            row.entityName = entityName;
            row.deferBlockBefore(blockBefore); row.deferBlockAfter(blockAfter);
            row.deferSnapshot(LogEntry.SnapshotField.ITEM, NbtSerde.snapshotItemStack(heldBefore, level.registryAccess()));
            var before=state(heldBefore,completed);var after=state(heldAfter,completed);transaction.attach(before,after,completed);
            incomplete=!after.getBoolean("Complete");
            row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);
            row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);
            row.count = heldAfter.isEmpty() ? heldBefore.getCount() : ItemStack.isSameItemSameComponents(heldBefore, heldAfter)
                    ? Math.max(0, heldBefore.getCount() - heldAfter.getCount()) : 0;
            row.extra = "create_deployer; mode=" + mode + "; result=" + (result == null ? "world_or_item_change" : result)
                    + "; complete=" + after.getBoolean("Complete") + "; owner_source=" + ownerSource;
            LogIdGenerator.ensure(row); return row;
        }
        private CompoundTag state(ItemStack held, boolean completed) {
            var tag = new CompoundTag(); tag.putInt("Format", 1); tag.putString("Kind", "deployer_action");
            tag.putString("Mode", mode); tag.putString("Result", result == null ? "world_or_item_change" : result);
            tag.putBoolean("Complete", completed); tag.putString("OwnerSource", ownerSource);
            if (result != null && result.equals("attack")) tag.putFloat("Damage", damage);
            if (owner != null) tag.putUUID("Owner", owner);
            var item = NbtSerde.snapshotItemStack(held, level.registryAccess());
            if (item != null) tag.put("HeldItem", item); else if (!held.isEmpty()) tag.putBoolean("Incomplete", true);
            return tag;
        }
        @Override public void close() {
            if (closed) return; closed = true;
            if(transaction!=null)transaction.close();
            cause.close(); if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            CreateActorContext.restore(previousActor); CreateMechanismContext.restore(previousMechanism);
            MutationSourceResolver.restore(previousSource);
        }
    }
}
