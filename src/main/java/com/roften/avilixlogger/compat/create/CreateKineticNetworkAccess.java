package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
public interface CreateKineticNetworkAccess {
    CompoundTag avilixlogger$network();
    void avilixlogger$network(ServerLevel level,CompoundTag tag);
}
