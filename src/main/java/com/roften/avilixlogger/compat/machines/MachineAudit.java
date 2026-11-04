package com.roften.avilixlogger.compat.machines;

import com.roften.avilixlogger.compat.teams.ModStateAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Loaded physical blocks only. Never resolves controllers, neighbours or capabilities. */
public final class MachineAudit {
    private MachineAudit() {}
    public static ServerLevel level(Object value) {
        if (!(value instanceof BlockEntity be) || !(be.getLevel() instanceof ServerLevel level)
                || !WorldSnapshotAccess.readable(level, be.getBlockPos()) || be.isRemoved()
                || level.getBlockEntity(be.getBlockPos()) != be) return null;
        return level;
    }
    public static boolean enabled(BlockEntity be, ActionType type) {
        var level = level(be);
        return level != null && policy(be, type);
    }
    public static boolean policy(BlockEntity be, ActionType type) {
        return be.getLevel() instanceof ServerLevel level && ModStateAudit.enabled(level.getServer(), type);
    }
    public static CompoundTag location(BlockEntity be) {
        var tag = new CompoundTag(); var pos = be.getBlockPos();
        tag.putString("Dimension", be.getLevel().dimension().location().toString());
        tag.putInt("X", pos.getX()); tag.putInt("Y", pos.getY()); tag.putInt("Z", pos.getZ());
        tag.putString("Block", BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock()).toString());
        tag.put("BlockState", net.minecraft.nbt.NbtUtils.writeBlockState(be.getBlockState()));
        return tag;
    }
    public static void failure(String source) { AdaptiveLogDiagnostics.adapterFailure(source); }
}
