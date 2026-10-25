package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Observing a block must neither create a chunk ticket nor race a mod's mutable world data. */
public final class WorldSnapshotAccess {
    private WorldSnapshotAccess() {}
    public static boolean readable(ServerLevel level, BlockPos pos) {
        return level != null && pos != null && level.getServer().isSameThread() && level.hasChunkAt(pos);
    }
}
