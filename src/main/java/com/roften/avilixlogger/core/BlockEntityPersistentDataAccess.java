package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;

/** Full-snapshot restore must clear persistent data absent from the historical state. */
public interface BlockEntityPersistentDataAccess {
    void avilixlogger$restorePersistentData(CompoundTag data);
}
