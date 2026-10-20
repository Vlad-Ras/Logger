package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.CompoundTag;
/** Runtime fields declared by a controller subclass, absent from native persistence. */
public interface CreateControllerRuntimeAccess {
    CompoundTag avilixlogger$extraRuntime();
    void avilixlogger$extraRuntime(CompoundTag tag);
}
