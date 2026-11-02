package com.roften.avilixlogger.compat.enchantmentindustry.mixin;
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
@Mixin(targets = {"plus.dragons.createenchantmentindustry.common.processing.enchanter.BlazeEnchanterBlockEntity","plus.dragons.createenchantmentindustry.common.processing.classic_enchanter.ClassicBlazeEnchanterBlockEntity"}, remap = false)
public abstract class HeldItemMixin {
    @WrapMethod(method = "insertItem(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$insert(ItemStack stack, boolean simulate, Operation<ItemStack> original){
        var action=simulate?null:IndustrialAudit.begin(this,"insert-held",true); boolean success=false;
        try{var result=original.call(stack, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "extractItem(ZZ)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$extract(boolean force, boolean simulate, Operation<ItemStack> original){
        var action=simulate?null:IndustrialAudit.begin(this,"extract-held",true); boolean success=false;
        try{var result=original.call(force, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"clear-held",true); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
