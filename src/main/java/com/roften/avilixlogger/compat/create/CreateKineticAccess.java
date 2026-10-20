package com.roften.avilixlogger.compat.create;
/** Chunk-load semantics for a kinetic BE restored in place, rather than placed after movement. */
public interface CreateKineticAccess {
    void avilixlogger$prepareSnapshotLoad();
    net.minecraft.nbt.CompoundTag avilixlogger$kineticRuntime();
    void avilixlogger$kineticRuntime(net.minecraft.nbt.CompoundTag tag);
}
