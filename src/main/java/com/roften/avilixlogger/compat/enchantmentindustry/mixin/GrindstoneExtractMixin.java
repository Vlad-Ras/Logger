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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.kinetics.grindstone.GrindstoneDrainBlockEntity$1", remap = false)
public abstract class GrindstoneExtractMixin {
    @WrapMethod(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_GRINDSTONEEXTRACTMIXIN_EXTRACT.enabled()) { return original.call(slot, amount, simulate); }
        var action=IndustrialAudit.item(this,simulate); boolean success=false;
        try{var result=original.call(slot, amount, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
}
