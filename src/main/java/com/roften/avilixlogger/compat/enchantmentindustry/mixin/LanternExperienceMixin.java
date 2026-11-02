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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.fluids.lantern.ExperienceLanternBlockEntity", remap = false)
public abstract class LanternExperienceMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "drainExp()V",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;giveExperiencePoints(I)V"), remap = false)
    private void avilixlogger$drain(Player player,int amount,Operation<Void> original){
        var action=IndustrialPlayers.begin(player,"create_enchantment_industry","lantern-drained-experience",null,true);boolean success=false;
        try{original.call(player,amount);success=true;}finally{if(action!=null)action.finish(!success);}
    }
}
