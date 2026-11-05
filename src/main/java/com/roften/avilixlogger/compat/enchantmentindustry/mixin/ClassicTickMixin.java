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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.processing.classic_enchanter.ClassicBlazeEnchanterBlockEntity", remap = false)
public abstract class ClassicTickMixin {
    @WrapMethod(method = "tick()V", remap = false)
    private void avilixlogger$tick(Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_CLASSICTICKMIXIN_TICK.enabled()) { original.call(); return; }
        try(var cause=com.roften.avilixlogger.core.CauseContext.unattributed();var simulation=IndustrialAudit.simulate(this)){
            var action=IndustrialProcessing.commitTick(this);boolean success=false;
            try{original.call();success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}
