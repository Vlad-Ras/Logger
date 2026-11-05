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
@Mixin(targets = "com.jesz.createdieselgenerators.content.tools.ChemicalSprayerItem", remap = false)
public abstract class SprayerMixin {
    @WrapMethod(method = "onUseTick(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;I)V", remap = false)
    private void avilixlogger$spray(Level level,LivingEntity entity,ItemStack stack,int ticks,Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_SPRAYERMIXIN_SPRAY.enabled()) { original.call(level,entity,stack,ticks); return; }
        if(!(level instanceof ServerLevel serverLevel)||!serverLevel.getServer().isSameThread()){original.call(level,entity,stack,ticks);return;}
        try(var cause=entity instanceof Player player&&entity.isUsingItem()?com.roften.avilixlogger.core.CauseContext.push(player,com.roften.avilixlogger.core.CauseContext.Kind.USE_ITEM,entity.blockPosition(),stack):com.roften.avilixlogger.core.CauseContext.unattributed()){
            var action=ticks%2==0?IndustrialTools.focus(entity,stack):null;boolean success=false;
            try{original.call(level,entity,stack,ticks);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}
