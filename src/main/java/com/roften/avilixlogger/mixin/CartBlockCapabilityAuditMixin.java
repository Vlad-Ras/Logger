package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.BlockCapability;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BlockCapability.class,remap=false)
public abstract class CartBlockCapabilityAuditMixin {
    @Inject(method="getCapability",at=@At("RETURN"))
    private void avilixlogger$address(Level level,BlockPos pos,BlockState state,BlockEntity be,Object side,CallbackInfoReturnable<Object> ci){
        CartAutomationHooks.exposed(level,pos,side,ci.getReturnValue());
    }
}
