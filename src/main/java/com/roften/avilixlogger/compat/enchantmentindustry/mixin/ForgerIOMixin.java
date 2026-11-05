package com.roften.avilixlogger.compat.enchantmentindustry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.industry.IndustrialAudit;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="plus.dragons.createenchantmentindustry.common.processing.forger.BlazeForgerBlockEntity",remap=false)
public abstract class ForgerIOMixin {
    @WrapMethod(method="insertItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;",remap=false)
    private ItemStack avilixlogger$insert(ItemStack stack,boolean simulate,Operation<ItemStack> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERIOMIXIN_INSERT.enabled()) { return original.call(stack,simulate); }
        var action=simulate?null:IndustrialAudit.begin(this,"forger-insert",true);boolean success=false;
        try{var result=original.call(stack,simulate);success=true;return result;}finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="extractItem(Z)Lnet/minecraft/world/item/ItemStack;",remap=false)
    private ItemStack avilixlogger$extract(boolean simulate,Operation<ItemStack> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERIOMIXIN_EXTRACT.enabled()) { return original.call(simulate); }
        var action=simulate?null:IndustrialAudit.begin(this,"forger-extract",true);boolean success=false;
        try{var result=original.call(simulate);success=true;return result;}finally{if(action!=null)action.finish(!success);}
    }
}
