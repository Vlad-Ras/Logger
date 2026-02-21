package com.roften.avilixlogger.command;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.core.Direction;

/**
 * Helper to find the second half of a double chest, without hard dependency on MCP method names.
 */
final class ChestUtil {
    private static final EnumProperty<ChestType> TYPE = ChestBlock.TYPE;
    private static final DirectionProperty FACING = ChestBlock.FACING;

    private ChestUtil() {}

    /** @return other chest pos if state is LEFT/RIGHT, else null */
    static BlockPos getConnectedChestPos(Level level, BlockPos pos, BlockState state) {
        try {
            ChestType type = state.getValue(TYPE);
            if (type == ChestType.SINGLE) return null;
            Direction facing = state.getValue(FACING);
            // Vanilla offset rules: LEFT/RIGHT relative to facing.
            Direction offsetDir = (type == ChestType.LEFT) ? facing.getClockWise() : facing.getCounterClockWise();
            BlockPos other = pos.relative(offsetDir);
            // Validate that the other block is a chest.
            BlockState otherState = level.getBlockState(other);
            if (otherState.getBlock() instanceof ChestBlock) return other;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }
}
