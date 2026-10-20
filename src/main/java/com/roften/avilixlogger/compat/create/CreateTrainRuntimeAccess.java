package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
public interface CreateTrainRuntimeAccess {
    CompoundTag avilixlogger$trainRuntime(net.minecraft.core.HolderLookup.Provider registries);
    void avilixlogger$trainRuntime(CompoundTag tag,MinecraftServer server);
}
