package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.aeronautics.AeronauticsCompatHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Objects;

/**
 * Catch block changes that bypass NeoForge events (WorldEdit, Create Schematicannon, creative tools, etc.).
 *
 * External sources are passed through explicit action/tick context without stack walking.
 * The central storage gate removes overlap
 * with normal NeoForge events.
 */
@Mixin(Level.class)
public abstract class ServerLevelSetBlockMixin {

    @Unique
    private static final ThreadLocal<Boolean> AVILIXLOGGER$REENTRY_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** The initiating setBlock has already been captured. Block callbacks may mutate other
     * positions as part of world simulation; the initiating player is not their actor. */
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/LevelChunk;setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)Lnet/minecraft/world/level/block/state/BlockState;"), require = 1)
    private BlockState avilixlogger$blockCallbacks(LevelChunk chunk, BlockPos pos, BlockState state, boolean moving,
                                                    Operation<BlockState> original) {
        if (!((Object)this instanceof ServerLevel) || CartAuditContext.restoring() || CauseContext.peek() == null || avilixlogger$explicitMechanism()) return original.call(chunk, pos, state, moving);
        try (var scope = CauseContext.unattributed()) { return original.call(chunk, pos, state, moving); }
    }

    /** Neighbor and shape updates can synchronously trigger pistons, fluids, falling blocks, etc. */
    @WrapOperation(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;markAndNotifyBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;II)V"), require = 1)
    private void avilixlogger$neighborCallbacks(Level level, BlockPos pos, LevelChunk chunk, BlockState before,
                                                  BlockState after, int flags, int recursionLeft, Operation<Void> original) {
        if (!(level instanceof ServerLevel) || CartAuditContext.restoring() || CauseContext.peek() == null || avilixlogger$explicitMechanism()) { original.call(level, pos, chunk, before, after, flags, recursionLeft); return; }
        try (var scope = CauseContext.unattributed()) { original.call(level, pos, chunk, before, after, flags, recursionLeft); }
    }

    @Unique
    private static boolean avilixlogger$explicitMechanism() {
        return CartAuditContext.current() != null || CreateActorContext.current() != null
                || CreateMechanismContext.current() != null
                || CreateContraptionKind.id(MutationSourceResolver.resolveExternalSource()) != null;
    }

    // Capture before destroyBlock drops the controller item; setBlock starts too late for it.
    @WrapMethod(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z")
    private boolean avilixlogger$destroyController(BlockPos pos, boolean drops, net.minecraft.world.entity.Entity entity,
                                                    int recursionLeft, Operation<Boolean> original) {
        if (!((Object)this instanceof ServerLevel) || CartAuditContext.restoring()) return original.call(pos,drops,entity,recursionLeft);
        if(CartRestoreLocks.blockLocked((Level)(Object)this,pos))return false;
        try(var removal=CreateStructuralHooks.removal((Level)(Object)this,pos,net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())){
            boolean changed=original.call(pos,drops,entity,recursionLeft);
            if(removal!=null&&changed)removal.success();return changed;
        }
    }

    // NOTE: Do NOT declare helper record/class in this mixin package.
    // Mixin packages are restricted and cannot be referenced by transformed target classes.

    // Only the four-argument overload: the three-argument overload delegates to it.
    // A local snapshot survives recursion and exceptions without thread-local capture stacks.
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean avilixlogger$setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                         Operation<Boolean> original) {
        if (!((Object)this instanceof ServerLevel) || CartAuditContext.restoring()) return original.call(pos,newState,flags,recursionLeft);
        if (CartRestoreLocks.blockLocked((Level)(Object)this,pos)) return false;
        try(var removal=CreateStructuralHooks.removal((Level)(Object)this,pos,newState)){
            boolean structural=CreateStructuralHooks.before((Level)(Object)this,pos);
            boolean deployer=CreateDeployerHooks.before((Level)(Object)this,pos);
            SetBlockCapture snapshot = structural||deployer?null:capture(pos, newState);
            boolean changed = original.call(pos, newState, flags, recursionLeft);
            if (removal!=null&&changed)removal.success();
            if (snapshot != null && changed) after(snapshot, pos);
            return changed;
        }
    }

    @Unique
    private SetBlockCapture capture(BlockPos pos, BlockState newState) {
        boolean ownsGuard = false;
        try {
            if (pos == null || newState == null || CartAuditContext.restoring()) return null;
            if (Boolean.TRUE.equals(AVILIXLOGGER$REENTRY_GUARD.get())) return null;

            if (!((Object) this instanceof ServerLevel level)) return null;

            CartAuditContext.Stamp cart = CartAuditContext.current();
            CauseContext.Cause cause = CauseContext.peek();
            String source = MutationSourceResolver.resolveExternalSource();
            if (cause != null && CreateContraptionKind.id(source) == null) source = null;
            if (cart != null) source = cart.source();
            source = CreateMechanismContext.decorate(source);
            if (cart == null && cause == null && MutationSourceResolver.VANILLA_SIMULATION.equals(source)) return null;
            // Ignored simulation/client calls need no authorization lookup. Every captured
            // mutation still checks the current authorization before reading world snapshots.
            if (!LoggerConfig.isEnabled() || (cart==null && !LoggerConfig.VALUES.logBlocks.get())) return null;
            BlockState before = level.getBlockState(pos);
            if (before == null || before == newState) return null;
            if (source == null && cause == null) source = MutationSourceResolver.sourceFor(before.getBlock());
            if (source == null && cause == null) source = MutationSourceResolver.sourceFor(newState.getBlock());
            // Unknown mod callbacks still get an audit row; never guess a player or walk a stack.
            if (source == null && cause == null) source = "system:unattributed";
            if (source == null) source = "player:" + cause.kind().name().toLowerCase(java.util.Locale.ROOT);

            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.TRUE);
            ownsGuard = true;
            BlockState beforeState = before;
            BlockEntity be = before.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            var removed = cart == null ? null : CartAuditContext.takeRemovedBlock(pos);
            var beforeBe = removed != null ? removed.be() : be != null ? NbtSerde.snapshotBlockEntity(level, be) : null;
            var beforeSlots = removed != null ? removed.slots() : be != null ? ContainerSlotSnapshot.snapshotTag(level, pos, before, be) : null;

            java.util.UUID actorUuid = null;
            String actorName = null;

            try {
                if (cause != null && cause.actorUuid() != null) {
                    actorUuid = cause.actorUuid();
                    actorName = cause.actorName();
                }
            } catch (Throwable ignored) {}

            if (actorName == null) {
                actorName = "SYSTEM[" + source + "]";
            }
            return new SetBlockCapture(pos.immutable(),
                    level.dimension().location().toString(), beforeState, beforeBe, beforeSlots,
                    source, cause == null ? null : cause.kind(), actorUuid, actorName,
                    cart == null ? 0L : LogIdGenerator.next(System.currentTimeMillis()));
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (ownsGuard) AVILIXLOGGER$REENTRY_GUARD.set(Boolean.FALSE);
        }
    }

    @Unique
    private void after(SetBlockCapture cap, BlockPos pos) {
        if (pos == null || !pos.equals(cap.pos())) {
            // Should not happen, but keep stack consistent.
            return;
        }

        try {
            if (!((Object) this instanceof ServerLevel level)) return;

            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.TRUE);

            BlockState after = level.getBlockState(pos);
            BlockState afterState = after;
            BlockEntity beAfter = after.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            var afterBe = beAfter != null ? NbtSerde.snapshotBlockEntity(level, beAfter) : null;
            var afterSlots = beAfter != null ? ContainerSlotSnapshot.snapshotTag(level, pos, after, beAfter) : null;

            var cart=CartAuditContext.current();
            if(cart!=null && cart.phase().equals("disassemble") && afterBe!=null)CartAuditContext.rememberPlacedBlock(pos,afterBe,afterSlots);
            // Freeze all world state above. Comparison and SNBT encoding below use only snapshots.
            final long timestamp = System.currentTimeMillis();
            final String afterId = BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString();
            final LogStorage storage = LoggerRuntime.storage(level);
            if (cap.beforeBe() == null && afterBe == null && cap.beforeSlots() == null && afterSlots == null) {
                // No mutable NBT to compare: avoid a CPU task, its queue lock and a second handoff.
                if (Objects.equals(cap.beforeState(), afterState)) return;
                if (storage instanceof AsyncLogStorage async) {
                    async.appendBlockChange(cap, afterState, afterId, timestamp);
                    return;
                }
            }
            long bytes = 512L + (cap.beforeBe() == null ? 0 : cap.beforeBe().sizeInBytes())
                    + (afterBe == null ? 0 : afterBe.sizeInBytes())
                    + (cap.beforeSlots() == null ? 0 : cap.beforeSlots().sizeInBytes())
                    + (afterSlots == null ? 0 : afterSlots.sizeInBytes());
            AsyncLogProcessor.submit(bytes * 2L, () -> {
                if (Objects.equals(cap.beforeState(), afterState)
                        && Objects.equals(cap.beforeBe(), afterBe)
                        && Objects.equals(cap.beforeSlots(), afterSlots)) return;

                LogEntry e = cap.entry(afterState, afterId, timestamp);
                e.beBefore = NbtSerde.toSnbt(cap.beforeBe());
                e.beAfter = NbtSerde.toSnbt(afterBe);
                e.containerSlotsBefore = NbtSerde.toSnbt(cap.beforeSlots());
                e.containerSlotsAfter = NbtSerde.toSnbt(afterSlots);
                storage.append(e);
            });
        } catch (Throwable ignored) {
        } finally {
            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.FALSE);
        }
    }

}
