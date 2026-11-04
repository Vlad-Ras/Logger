package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.CompoundTag;
/** Create's writer consumes forceAngle; observational snapshots must preserve it. */
public interface CreateOrientationAccess {
    boolean avilixlogger$forceAngle();
    void avilixlogger$forceAngle(boolean value);
    CompoundTag avilixlogger$orientation();
    void avilixlogger$orientation(CompoundTag value);
}
