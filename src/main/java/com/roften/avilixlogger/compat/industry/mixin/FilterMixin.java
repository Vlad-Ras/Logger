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
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.behaviour.filtering.FilteringBehaviour", remap = false)
public abstract class FilterMixin {
    @WrapMethod(method = "setFilter(Lnet/minecraft/world/item/ItemStack;)Z", remap = false)
    private boolean avilixlogger$filter(ItemStack stack, Operation<Boolean> original) {
        if (!CaptureHandlers.INDUSTRY_FILTERMIXIN_FILTER.enabled()) { return original.call(stack); }
        var action = IndustrialAudit.behaviour(this,"filter"); boolean success = false;
        try { var result = original.call(stack); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
