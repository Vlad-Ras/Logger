package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartAutomationHooks;
import net.neoforged.neoforge.capabilities.BlockCapabilityCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value=BlockCapabilityCache.class,remap=false)
public abstract class CartCapabilityCacheAuditMixin {
    @Inject(method="getCapability",at=@At("RETURN"))
    private void avilixlogger$address(CallbackInfoReturnable<Object> ci){
        var cache=(BlockCapabilityCache<?,?>)(Object)this;
        CartAutomationHooks.exposed(cache.level(),cache.pos(),cache.context(),ci.getReturnValue());
    }
}
