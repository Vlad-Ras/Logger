package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.world.level.block.entity.Hopper;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.HopperBlock;
import net.neoforged.neoforge.items.VanillaInventoryCodeHooks;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=VanillaInventoryCodeHooks.class,remap=false)
public abstract class BaseCartNeoForgeHopperAuditMixin {
    @WrapMethod(method="lambda$extractHook$0")
    private static Boolean avilixlogger$take(Hopper hopper,Pair<?,?> base,Operation<Boolean> original){
        if(CartAutomationHooks.baseHopperLocked(hopper,base.getValue()))return false;
        var capture=CartAutomationHooks.baseHopper(hopper,base.getValue());boolean success=false;
        try{var result=original.call(hopper,base);success=true;return result;}
        finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="lambda$insertHook$2")
    private static Boolean avilixlogger$put(HopperBlockEntity hopper,Pair<?,?> base,Operation<Boolean> original){
        if(CartAutomationHooks.baseHopperLocked(hopper,base.getValue()))return false;
        var capture=CartAutomationHooks.baseHopper(hopper,base.getValue(),hopper.getBlockState().getValue(HopperBlock.FACING));boolean success=false;
        try{var result=original.call(hopper,base);success=true;return result;}
        finally{if(capture!=null)capture.finish(success);}
    }
}
