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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.kinetics.deployer.DeployerExtension", remap = false)
public abstract class DeployerExperienceMixin {
    @WrapMethod(method = "collectExperience(Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;I)V", remap = false)
    private static void avilixlogger$collect(@Coerce Object deployer, int amount, Operation<Void> original){
        try(var cause=com.roften.avilixlogger.core.CauseContext.unattributed()){
            var action=IndustrialPlayers.begin(deployer,"create_enchantment_industry","deployer-collected-experience",null,true);boolean success=false;
            try{original.call(deployer,amount);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}
