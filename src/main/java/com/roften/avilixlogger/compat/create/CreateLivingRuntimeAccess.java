package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

/** Non-NBT living state, captured only around an actual deployer activation. */
public interface CreateLivingRuntimeAccess {
    CompoundTag avilixlogger$livingRuntime(LivingEntity actor);
    void avilixlogger$livingRuntime(CompoundTag tag,LivingEntity actor);
    void avilixlogger$clearEffectsForRestore();
}
