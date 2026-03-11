package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Catch block changes that bypass NeoForge events (WorldEdit, Create Schematicannon, creative tools, etc.).
 *
 * We keep it narrow and only record when the call stack indicates a known external editor/automation.
 * This avoids duplicating normal player logs.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelSetBlockMixin {

    @Unique
    private static final ThreadLocal<Deque<SetBlockCapture>> AVILIXLOGGER$CAPTURE_STACK = ThreadLocal.withInitial(ArrayDeque::new);

    @Unique
    private static final ThreadLocal<Boolean> AVILIXLOGGER$REENTRY_GUARD = ThreadLocal.withInitial(() -> Boolean.FALSE);

    // NOTE: Do NOT declare helper record/class in this mixin package.
    // Mixin packages are restricted and cannot be referenced by transformed target classes.

    // Signature (BlockPos, BlockState, int)
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("HEAD"), require = 0)
    private void avilixlogger$capture3(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        capture(pos, newState);
    }

    // Signature (BlockPos, BlockState, int, int)
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"), require = 0)
    private void avilixlogger$capture4(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        capture(pos, newState);
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z",
            at = @At("RETURN"), require = 0)
    private void avilixlogger$after3(BlockPos pos, BlockState newState, int flags, CallbackInfoReturnable<Boolean> cir) {
        after(pos, newState, cir.getReturnValueZ());
    }

    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("RETURN"), require = 0)
    private void avilixlogger$after4(BlockPos pos, BlockState newState, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir) {
        after(pos, newState, cir.getReturnValueZ());
    }

    @Unique
    private void capture(BlockPos pos, BlockState newState) {
        try {
            // Config lives in root package (not in core).
            if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logBlocks.get()) return;
            if (pos == null || newState == null) return;
            if (Boolean.TRUE.equals(AVILIXLOGGER$REENTRY_GUARD.get())) return;

            ServerLevel level = (ServerLevel) (Object) this;
            if (level.isClientSide) return;

            String source = detectExternalSource();
            if (source == null) return; // keep narrow to avoid duplicates

            BlockState before = level.getBlockState(pos);
            if (before == null) return;

            // If nothing changes, ignore.
            if (before == newState) return;

            String beforeState = NbtSerde.writeBlockState(before);
            BlockEntity be = level.getBlockEntity(pos);
            String beforeBe = LoggerRuntime.isUnderPressure(level) ? null : NbtSerde.writeBlockEntity(level, be);

            java.util.UUID actorUuid = null;
            String actorName = null;

            if (source.startsWith("create")) {
                var ra = CreateOwnershipTracker.resolveForSystemChange(level, pos, null);
                if (ra != null) {
                    actorUuid = ra.uuid();
                    actorName = (ra.name() != null && !ra.name().isBlank()) ? ra.name() : null;
                    source = ra.source();
                }
            }
            if (actorName == null) {
                actorName = source.startsWith("worldedit") ? "WorldEdit" : (source.startsWith("create") ? "Create" : "SYSTEM");
            }

            AVILIXLOGGER$CAPTURE_STACK.get().addLast(new SetBlockCapture(new BlockPos(pos.getX(), pos.getY(), pos.getZ()), level.dimension().location().toString(), beforeState, beforeBe, source, actorUuid, actorName));
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void after(BlockPos pos, BlockState newState, boolean ok) {
        Deque<SetBlockCapture> st = AVILIXLOGGER$CAPTURE_STACK.get();
        if (st.isEmpty()) return;
        SetBlockCapture cap = st.removeLast();
        if (cap == null) return;
        if (!ok) return;
        if (pos == null || !pos.equals(cap.pos())) {
            // Should not happen, but keep stack consistent.
            return;
        }

        try {
            ServerLevel level = (ServerLevel) (Object) this;
            if (level.isClientSide) return;

            AVILIXLOGGER$REENTRY_GUARD.set(Boolean.TRUE);

            BlockState after = level.getBlockState(pos);
            String afterState = NbtSerde.writeBlockState(after);
            BlockEntity beAfter = level.getBlockEntity(pos);
            String afterBe = LoggerRuntime.isUnderPressure(level) ? null : NbtSerde.writeBlockEntity(level, beAfter);

            ActionType type;
            boolean beforeAir = beforeIsAir(cap.beforeState());
            boolean afterAir = after == null || after.isAir();
            if (beforeAir && !afterAir) type = ActionType.BLOCK_PLACE;
            else if (!beforeAir && afterAir) type = ActionType.BLOCK_BREAK;
            else type = ActionType.BLOCK_ENTITY_NBT_CHANGE;

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

    @Unique
    private static String detectExternalSource() {
        try {
            final boolean[] create = {false};
            final boolean[] we = {false};

            java.lang.StackWalker.getInstance().walk(s -> {
                s.limit(28).forEach(f -> {
                    String cn = f.getClassName();
                    if (cn == null) return;
                    if (!create[0] && cn.startsWith("com.simibubi.create")) create[0] = true;
                    if (!we[0] && cn.startsWith("com.sk89q.worldedit")) we[0] = true;
                });
                return null;
            });

            if (we[0]) return "worldedit";
            if (create[0]) return "create";
        } catch (Throwable ignored) {
        }
        return null;
    }
}
