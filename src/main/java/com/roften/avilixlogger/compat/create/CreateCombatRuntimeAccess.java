package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;

public interface CreateCombatRuntimeAccess {
    CompoundTag avilixlogger$combatRuntime(LivingEntity actor);
    void avilixlogger$combatRuntime(CompoundTag tag,LivingEntity actor);
}
