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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.fluids.experience.ExperienceHelper", remap = false)
public abstract class XpHelperMixin {
    @WrapMethod(method = "award(ILnet/minecraft/server/level/ServerPlayer;)V", remap = false)
    private static void avilixlogger$award(int amount, ServerPlayer player, Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_XPHELPERMIXIN_AWARD.enabled()) { original.call(amount, player); return; }
        var action=IndustrialPlayers.begin(player,"create_enchantment_industry","experience-award",null,true); boolean success=false;
        try{original.call(amount, player);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "repairItem(ILnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/ItemStack;Z)I", remap = false)
    private static int avilixlogger$repair(int amount, ServerLevel level, ItemStack stack, boolean simulate, Operation<Integer> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_XPHELPERMIXIN_REPAIR.enabled()) { return original.call(amount, level, stack, simulate); }
        var action=IndustrialPlayers.repair(level,stack,simulate); boolean success=false;
        try{var result=original.call(amount, level, stack, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="repairPlayerItems(Lnet/minecraft/server/level/ServerPlayer;I)I",remap=false)
    private static int avilixlogger$repairPlayer(ServerPlayer player,int amount,Operation<Integer> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_XPHELPERMIXIN_REPAIRPLAYER.enabled()) { return original.call(player,amount); }
        var action=IndustrialPlayers.begin(player,"create_enchantment_industry","repair-player-items",null,true);boolean success=false;
        try{int result=original.call(player,amount);success=true;return result;}finally{if(action!=null)action.finish(!success);}
    }
}
