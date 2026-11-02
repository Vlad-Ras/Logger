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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.fluids.experience.ExperienceHatchBlock", remap = false)
public abstract class HatchExperienceMixin {
    @WrapMethod(method = "useWithoutItem(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/InteractionResult;", remap = false)
    private net.minecraft.world.InteractionResult avilixlogger$hatch(net.minecraft.world.level.block.state.BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.phys.BlockHitResult hit, Operation<net.minecraft.world.InteractionResult> original){
        if(!(level instanceof ServerLevel serverLevel)||!serverLevel.getServer().isSameThread())return original.call(state,level,pos,player,hit);
        try(var cause=com.roften.avilixlogger.core.CauseContext.push(player,com.roften.avilixlogger.core.CauseContext.Kind.USE_BLOCK,pos,ItemStack.EMPTY)){
            var action=IndustrialPlayers.begin(player,"create_enchantment_industry","experience-hatch",null,true);boolean success=false;
            try{var result=original.call(state,level,pos,player,hit);success=true;return result;}finally{if(action!=null)action.finish(!success);}
        }
    }
}
