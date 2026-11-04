package com.roften.avilixlogger.compat.ftbteams;
import net.minecraft.nbt.CompoundTag;
/** Detached observation baseline, owned by the native team lifetime. */
public interface FtbTeamState {
    CompoundTag avilixlogger$baseline();
    void avilixlogger$baseline(CompoundTag tag);
}
