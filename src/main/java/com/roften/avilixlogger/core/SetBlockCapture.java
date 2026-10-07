package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Small DTO used by {@code ServerLevelSetBlockMixin} to remember pre-state around a setBlock call.
 *
 * <p>IMPORTANT: This class must NOT live in the mixin package. Mixin packages are restricted and
 * classes from them cannot be referenced by transformed target classes.</p>
 */
public record SetBlockCapture(BlockPos pos, String dim, net.minecraft.world.level.block.state.BlockState beforeState, net.minecraft.nbt.CompoundTag beforeBe, net.minecraft.nbt.CompoundTag beforeSlots,
                              String source, CauseContext.Kind causeKind, UUID actorUuid, String actorName, long capturedId) {
    public SetBlockCapture(BlockPos pos, String dim, BlockState state, net.minecraft.nbt.CompoundTag be, net.minecraft.nbt.CompoundTag slots,
            String source, CauseContext.Kind kind, UUID actor, String name) {
        this(pos, dim, state, be, slots, source, kind, actor, name, 0L);
    }
    /** Plain state changes need only the event queue; block-state encoding stays on its worker. */
    public LogEntry entry(BlockState after, String afterId, long timestamp) {
        LogEntry row = new LogEntry();
        row.id = capturedId;
        row.ts = timestamp;
        row.dim = dim;
        row.type = classify(beforeState.isAir(), after.isAir(), !java.util.Objects.equals(beforeState, after), causeKind);
        row.actorUuid = actorUuid;
        row.actorName = actorName;
        row.x = pos.getX(); row.y = pos.getY(); row.z = pos.getZ();
        row.deferBlockBefore(beforeState);
        row.deferBlockAfter(after);
        row.source = source;
        row.extra = "setBlock " + afterId;
        return row;
    }

    static ActionType classify(boolean beforeAir, boolean afterAir, boolean stateChanged, CauseContext.Kind cause) {
        if (beforeAir && !afterAir) return ActionType.BLOCK_PLACE;
        if (!beforeAir && afterAir) return ActionType.BLOCK_BREAK;
        if (cause == CauseContext.Kind.USE_BLOCK || cause == CauseContext.Kind.USE_ITEM) return ActionType.BLOCK_INTERACT;
        return stateChanged ? ActionType.BLOCK_PLACE : ActionType.BLOCK_ENTITY_NBT_CHANGE;
    }
}
