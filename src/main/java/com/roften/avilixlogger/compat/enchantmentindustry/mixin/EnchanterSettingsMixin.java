package com.roften.avilixlogger.compat.enchantmentindustry.mixin;

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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.processing.enchanter.EnchanterBehaviour", remap = false)
public abstract class EnchanterSettingsMixin {
    @WrapMethod(method = "setTemplate(Lnet/minecraft/world/item/ItemStack;)Z", remap = false)
    private boolean avilixlogger$template(ItemStack stack, Operation<Boolean> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_ENCHANTERSETTINGSMIXIN_TEMPLATE.enabled()) { return original.call(stack); }
        var action=IndustrialAudit.behaviour(this,"enchanting-template"); boolean success=false;
        try{var result=original.call(stack);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "setValue(I)V", remap = false)
    private void avilixlogger$value(int value, Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_ENCHANTERSETTINGSMIXIN_VALUE.enabled()) { original.call(value); return; }
        var action=IndustrialAudit.behaviour(this,"enchanting-level"); boolean success=false;
        try{original.call(value);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
