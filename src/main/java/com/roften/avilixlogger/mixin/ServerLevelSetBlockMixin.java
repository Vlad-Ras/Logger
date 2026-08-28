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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.Objects;

/**
 * Catch block changes that bypass NeoForge events (WorldEdit, Create Schematicannon, creative tools, etc.).
 *
 * External sources are resolved from their call-site dynamically. This makes the hook work for
 * mods that were not known when the logger was built; the central storage gate removes overlap
 * with normal NeoForge events.
 */
@Mixin(Level.class)
public abstract class ServerLevelSetBlockMixin {

    @Unique
    private static final ThreadLocal<Deque<Optional<SetBlockCapture>>> AVILIXLOGGER$CAPTURE_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Unique
    private static final ThreadLocal<Boolean> AVILIXLOGGER$REENTRY_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // NOTE: Do NOT declare helper record/class in this mixin package.
    // Mixin packages are restricted and cannot be referenced by transformed target classes.

    // The three-argument Level#setBlock delegates here. Hooking both overloads would serialize
    // every ordinary block change twice before the central deduplicator can remove the second row.
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), require = 0)
    private void avilixlogger$capture4(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        capture(pos, newState);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"), require = 0)
    private void avilixlogger$after4(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        after(pos, newState, cir.getReturnValueZ());
    }

    @Unique
    private void capture(BlockPos pos, BlockState newState) {
        Deque<Optional<SetBlockCapture>> stack = AVILIXLOGGER$CAPTURE_STACK.get();
        stack.addLast(Optional.empty());
        try {
            // Config lives in root package (not in core).
            if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logBlocks.get()) return;
            if (pos == null || newState == null) return;
            if (Boolean.TRUE.equals(AVILIXLOGGER$REENTRY_GUARD.get())) return;

            if (!((Object) this instanceof ServerLevel level)) return;

            CauseContext.Cause cause = CauseContext.peek();
            // StackWalker is useful for unknown mod mutations, but wasteful for the overwhelmingly
            // common player path where the cause context already gives us exact attribution.
            String source = cause == null ? MutationSourceResolver.resolveExternalSource() : null;
            if (source == null && cause == null) return;
            if (source == null) source = "player:" + cause.kind().name().toLowerCase(java.util.Locale.ROOT);

            BlockState before = level.getBlockState(pos);
            if (before == null) return;

            // If nothing changes, ignore.
            if (before == newState) return;

            String beforeState = NbtSerde.writeBlockState(before);
            BlockEntity be = level.getBlockEntity(pos);
            String beforeBe = (be != null || newState.hasBlockEntity()) ? NbtSerde.writeBlockEntity(level, be) : null;
            String beforeSlots = be != null ? ContainerSlotSnapshot.snapshot(level, pos) : null;

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
            if (actorUuid == null && actorName == null) {
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
            stack.removeLast();
            stack.addLast(Optional.of(new SetBlockCapture(new BlockPos(pos.getX(), pos.getY(), pos.getZ()),
                    level.dimension().location().toString(), beforeState, beforeBe, beforeSlots,
                    source, cause == null ? null : cause.kind(), actorUuid, actorName)));
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void after(BlockPos pos, BlockState newState, boolean ok) {
        Deque<Optional<SetBlockCapture>> st = AVILIXLOGGER$CAPTURE_STACK.get();
        if (st.isEmpty()) return;
        Optional<SetBlockCapture> captured = st.removeLast();
        if (captured.isEmpty()) return;
        SetBlockCapture cap = captured.get();
        if (!ok) return;
        if (pos == null || !pos.equals(cap.pos())) {
            // Should not happen, but keep stack consistent.
            return;
        }

        try {
            if (!((Object) this instanceof ServerLevel level)) return;

            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.TRUE);

            BlockState after = level.getBlockState(pos);
            String afterState = NbtSerde.writeBlockState(after);
            BlockEntity beAfter = level.getBlockEntity(pos);
            String afterBe = (beAfter != null || cap.beforeBe() != null) ? NbtSerde.writeBlockEntity(level, beAfter) : null;
            String afterSlots = beAfter != null || cap.beforeSlots() != null ? ContainerSlotSnapshot.snapshot(level, pos) : null;

            if (Objects.equals(cap.beforeState(), afterState)
                    && Objects.equals(cap.beforeBe(), afterBe)
                    && Objects.equals(cap.beforeSlots(), afterSlots)) return;

            ActionType type;
            boolean beforeAir = beforeIsAir(cap.beforeState());
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
            e.ts = System.currentTimeMillis();
            e.dim = cap.dim();
            e.type = type;
            e.actorUuid = cap.actorUuid();
            e.actorName = cap.actorName();
            e.x = pos.getX();
            e.y = pos.getY();
            e.z = pos.getZ();
            e.blockBefore = cap.beforeState();
            e.beBefore = cap.beforeBe();
            e.blockAfter = afterState;
            e.beAfter = afterBe;
            e.containerSlotsBefore = cap.beforeSlots();
            e.containerSlotsAfter = afterSlots;
            e.source = cap.source();

            try {
                if (after != null) {
                    var id = BuiltInRegistries.BLOCK.getKey(after.getBlock());
                    if (id != null) e.extra = "setBlock " + id;
                }
            } catch (Throwable ignored) {}

            LoggerRuntime.storage(level).append(e);
        } catch (Throwable ignored) {
        } finally {
            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.FALSE);
        }
    }

    @Unique
    private static boolean beforeIsAir(String beforeStateSnbt) {
        if (beforeStateSnbt == null) return true;
        // Cheap heuristic without parsing NBT.
        // NbtUtils.writeBlockState encodes "Name:"minecraft:air"" for air.
        return beforeStateSnbt.contains("minecraft:air") || beforeStateSnbt.contains("minecraft:cave_air") || beforeStateSnbt.contains("minecraft:void_air");
    }

}
