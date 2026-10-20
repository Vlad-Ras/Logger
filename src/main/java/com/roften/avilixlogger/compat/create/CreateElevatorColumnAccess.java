package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;

/** Detached native column state; registration is managed by the transaction. */
public interface CreateElevatorColumnAccess {
    CompoundTag avilixlogger$column();
    void avilixlogger$column(CompoundTag state);
}
