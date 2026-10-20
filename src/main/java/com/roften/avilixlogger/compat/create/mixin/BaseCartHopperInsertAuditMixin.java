package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(HopperBlockEntity.class)
public abstract class BaseCartHopperInsertAuditMixin {
    @Unique private static final ThreadLocal<CartAutomationHooks.HopperCall> avilixlogger$ejection=ThreadLocal.withInitial(CartAutomationHooks.HopperCall::new);
    @WrapMethod(method="ejectItems")
    private static boolean avilixlogger$eject(Level level,BlockPos pos,HopperBlockEntity hopper,Operation<Boolean> original){
        if(!CartAutomationHooks.installed())return original.call(level,pos,hopper);
        var previous=avilixlogger$ejection.get();var frame=previous.active?new CartAutomationHooks.HopperCall():previous;
        frame.active=true;frame.capture=null;avilixlogger$ejection.set(frame);boolean success=false;
        try{var result=original.call(level,pos,hopper);success=true;return result;}
        finally{try{if(frame.capture!=null)frame.capture.finish(success);}finally{frame.capture=null;frame.active=false;avilixlogger$ejection.set(previous);}}
    }
    @WrapOperation(method="ejectItems",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/block/entity/HopperBlockEntity;getAttachedContainer(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/HopperBlockEntity;)Lnet/minecraft/world/Container;"))
    private static Container avilixlogger$selected(Level level,BlockPos pos,HopperBlockEntity hopper,Operation<Container> original){
        var base=original.call(level,pos,hopper);
        if(base==null)return null;
        if(CartAutomationHooks.baseHopperLocked(hopper,base))return null;
        var frame=avilixlogger$ejection.get();
        if(frame.active)frame.capture=CartAutomationHooks.baseHopper(hopper,base,hopper.getBlockState().getValue(HopperBlock.FACING));
        return base;
    }
}
