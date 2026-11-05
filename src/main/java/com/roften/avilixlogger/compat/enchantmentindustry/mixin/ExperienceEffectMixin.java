package com.roften.avilixlogger.compat.enchantmentindustry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.industry.IndustrialEffects;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="plus.dragons.createenchantmentindustry.common.fluids.experience.ExperienceEffectHandler",remap=false)
public abstract class ExperienceEffectMixin {
    @WrapMethod(method="consume(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/phys/AABB;Lnet/neoforged/neoforge/fluids/FluidStack;)I",remap=false)
    private int avilixlogger$consume(Level level,AABB area,FluidStack fluid,Operation<Integer> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_EXPERIENCEEFFECTMIXIN_CONSUME.enabled()) { return original.call(level,area,fluid); }
        try(var cause=CauseContext.unattributed()){
            int result=original.call(level,area,fluid);IndustrialEffects.consumed(level,area,fluid,result);return result;
        }
    }
}
