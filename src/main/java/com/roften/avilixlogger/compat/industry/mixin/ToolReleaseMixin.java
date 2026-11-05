package com.roften.avilixlogger.compat.industry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "net.minecraft.world.item.Item", remap = false)
public abstract class ToolReleaseMixin {
    @WrapMethod(method = "releaseUsing(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V", remap = false)
    private void avilixlogger$release(ItemStack stack, Level level, LivingEntity entity, int ticks, Operation<Void> original){
        if (!CaptureHandlers.INDUSTRY_TOOLRELEASEMIXIN_RELEASE.enabled()) { original.call(stack,level,entity,ticks); return; }
        try{original.call(stack,level,entity,ticks);}finally{IndustrialTools.release(stack);}
    }
}
