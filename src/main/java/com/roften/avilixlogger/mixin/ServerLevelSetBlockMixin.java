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

    // NOTE: Do NOT declare helper record/class in this mixin package.
    // Mixin packages are restricted and cannot be referenced by transformed target classes.

    // Only the four-argument overload: the three-argument overload delegates to it.
    // A local snapshot survives recursion and exceptions without thread-local capture stacks.
    @WrapMethod(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z")
    private boolean avilixlogger$setBlock(BlockPos pos, BlockState newState, int flags, int recursionLeft,
                                         Operation<Boolean> original) {
        SetBlockCapture snapshot = capture(pos, newState);
        boolean changed = original.call(pos, newState, flags, recursionLeft);
        if (snapshot != null && changed) after(snapshot, pos);
        return changed;
    }

    @Unique
    private SetBlockCapture capture(BlockPos pos, BlockState newState) {
        boolean ownsGuard = false;
        try {
            // Config lives in root package (not in core).
            if (!LoggerConfig.isEnabled() || !LoggerConfig.VALUES.logBlocks.get()) return null;
            if (pos == null || newState == null) return null;
            if (Boolean.TRUE.equals(AVILIXLOGGER$REENTRY_GUARD.get())) return null;

            if (!((Object) this instanceof ServerLevel level)) return null;

            CauseContext.Cause cause = CauseContext.peek();
            String source = cause == null ? MutationSourceResolver.resolveExternalSource() : null;
            if (cause == null && MutationSourceResolver.VANILLA_SIMULATION.equals(source)) return null;
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
            var beforeBe = be != null ? NbtSerde.snapshotBlockEntity(level, be) : null;
            var beforeSlots = be != null ? ContainerSlotSnapshot.snapshotTag(level, pos, before, be) : null;

            java.util.UUID actorUuid = null;
            String actorName = null;

            try {
                if (cause != null && cause.actorUuid() != null) {
                    actorUuid = cause.actorUuid();
                    actorName = cause.actorName();
                }
            } catch (Throwable ignored) {}

            if ((actorUuid == null && actorName == null) && source.contains("aeronautics")) {
                var ar = AeronauticsCompatHooks.resolveActorForBlock(level, pos);
                if (ar != null) {
                    actorUuid = ar.actorUuid();
                    actorName = (ar.actorName() != null && !ar.actorName().isBlank()) ? ar.actorName() : null;
                }
            }

            if ((actorUuid == null && actorName == null) && source.contains("create")) {
                var ra = CreateOwnershipTracker.resolveForSystemChange(level, pos, null);
                if (ra != null) {
                    actorUuid = ra.uuid();
                    actorName = (ra.name() != null && !ra.name().isBlank()) ? ra.name() : null;
                    source = ra.source();
                }
            }
            if (actorUuid == null && actorName == null && !source.equals("system:unattributed")) {
                var recent = RecentPlayerActionTracker.resolveBest(level, pos, 4, 2_500L, null);
                if (recent != null && recent.confidence() >= 0.55) {
                    actorUuid = recent.actorUuid();
                    actorName = recent.actorName();
                    source = source + ":" + recent.source();
                }
            }
            if (actorName == null) {
                actorName = "SYSTEM[" + source + "]";
            }
            return new SetBlockCapture(new BlockPos(pos.getX(), pos.getY(), pos.getZ()),
                    level.dimension().location().toString(), beforeState, beforeBe, beforeSlots,
                    source, cause == null ? null : cause.kind(), actorUuid, actorName);
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

            // Freeze all world state above. Comparison and SNBT encoding below use only snapshots.
            final long timestamp = System.currentTimeMillis();
            final String afterId = BuiltInRegistries.BLOCK.getKey(after.getBlock()).toString();
            final LogStorage storage = LoggerRuntime.storage(level);
            long bytes = 512L + (cap.beforeBe() == null ? 0 : cap.beforeBe().sizeInBytes())
                    + (afterBe == null ? 0 : afterBe.sizeInBytes())
                    + (cap.beforeSlots() == null ? 0 : cap.beforeSlots().sizeInBytes())
                    + (afterSlots == null ? 0 : afterSlots.sizeInBytes());
            AsyncLogProcessor.submit(bytes * 2L, () -> {
                if (Objects.equals(cap.beforeState(), afterState)
                        && Objects.equals(cap.beforeBe(), afterBe)
                        && Objects.equals(cap.beforeSlots(), afterSlots)) return;

                ActionType type;
                boolean beforeAir = cap.beforeState().isAir();
                boolean afterAir = after == null || after.isAir();
                if (beforeAir && !afterAir) type = ActionType.BLOCK_PLACE;
                else if (!beforeAir && afterAir) type = ActionType.BLOCK_BREAK;
                else if (cap.causeKind() == CauseContext.Kind.USE_BLOCK || cap.causeKind() == CauseContext.Kind.USE_ITEM) {
                    type = ActionType.BLOCK_INTERACT;
                } else if (!Objects.equals(cap.beforeState(), afterState)) {
                    type = ActionType.BLOCK_PLACE;
                } else {
                    type = ActionType.BLOCK_ENTITY_NBT_CHANGE;
                }

                LogEntry e = new LogEntry();
                e.ts = timestamp;
                e.dim = cap.dim();
                e.type = type;
                e.actorUuid = cap.actorUuid();
                e.actorName = cap.actorName();
                e.x = cap.pos().getX();
                e.y = cap.pos().getY();
                e.z = cap.pos().getZ();
                e.blockBefore = NbtSerde.writeBlockState(cap.beforeState());
                e.beBefore = NbtSerde.toSnbt(cap.beforeBe());
                e.blockAfter = NbtSerde.writeBlockState(afterState);
                e.beAfter = NbtSerde.toSnbt(afterBe);
                e.containerSlotsBefore = NbtSerde.toSnbt(cap.beforeSlots());
                e.containerSlotsAfter = NbtSerde.toSnbt(afterSlots);
                e.source = cap.source();

                e.extra = "setBlock " + afterId;
                storage.append(e);
            });
        } catch (Throwable ignored) {
        } finally {
            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.FALSE);
        }
    }

}
