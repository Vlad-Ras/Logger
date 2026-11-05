package com.roften.avilixlogger.compat.dieselgenerators.mixin;

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
@Mixin(targets = "com.jesz.createdieselgenerators.content.tools.hammer.HammerItem", remap = false)
public abstract class HammerMixin {
    @WrapMethod(method = "use(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;", remap = false)
    private net.minecraft.world.InteractionResultHolder<ItemStack> avilixlogger$use(Level level, Player player, net.minecraft.world.InteractionHand hand, Operation<net.minecraft.world.InteractionResultHolder<ItemStack>> original){
        if (!CaptureHandlers.DIESELGENERATORS_HAMMERMIXIN_USE.enabled()) { return original.call(level, player, hand); }
        var action=IndustrialPlayers.begin(player,"createdieselgenerators","hammer-start",player.getItemInHand(hand),false); boolean success=false;
        try{var result=original.call(level, player, hand);if(action!=null)action.result(result);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "finishUsingItem(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$finish(ItemStack stack, Level level, LivingEntity entity, Operation<ItemStack> original){
        if (!CaptureHandlers.DIESELGENERATORS_HAMMERMIXIN_FINISH.enabled()) { return original.call(stack, level, entity); }
        var action=IndustrialPlayers.begin(entity,"createdieselgenerators","hammer-finish",stack,false); boolean success=false;
        try{var result=original.call(stack, level, entity);if(action!=null)action.result(result);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "releaseUsing(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;I)V", remap = false)
    private void avilixlogger$release(ItemStack stack, Level level, LivingEntity entity, int ticks, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_HAMMERMIXIN_RELEASE.enabled()) { original.call(stack, level, entity, ticks); return; }
        var action=IndustrialPlayers.begin(entity,"createdieselgenerators","hammer-return-input",stack,false); boolean success=false;
        try{original.call(stack, level, entity, ticks);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
