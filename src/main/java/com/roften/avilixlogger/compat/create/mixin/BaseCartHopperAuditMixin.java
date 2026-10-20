package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.core.Direction;
import net.minecraft.world.Container;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HopperBlockEntity.class)
public abstract class BaseCartHopperAuditMixin {
    @WrapMethod(method="tryTakeInItemFromSlot")
    private static boolean avilixlogger$take(Hopper hopper,Container base,int slot,Direction side,Operation<Boolean> original){
        if(CartAutomationHooks.baseHopperLocked(hopper,base))return false;
        if(base.getItem(slot).isEmpty())return original.call(hopper,base,slot,side);
        var capture=CartAutomationHooks.baseHopper(hopper,base);boolean success=false;
        try{var result=original.call(hopper,base,slot,side);success=true;return result;}
        finally{if(capture!=null)capture.finish(success);}
    }
}
