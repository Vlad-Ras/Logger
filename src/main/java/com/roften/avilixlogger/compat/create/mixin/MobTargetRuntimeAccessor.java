package com.roften.avilixlogger.compat.create.mixin;

import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Mob.class)
public interface MobTargetRuntimeAccessor {
    @Accessor("target") void avilixlogger$target(LivingEntity entity);
}
