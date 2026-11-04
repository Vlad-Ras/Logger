package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateCartRegistry;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.contraptions.minecart.capability.CapabilityMinecartController;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=CapabilityMinecartController.class,remap=false)
public abstract class MinecartRegistryLockMixin {
    @Inject(method="tick",at=@At("HEAD"),cancellable=true,require=1)
    private static void avilixlogger$locked(CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateCartRegistry.MUTATION))ci.cancel();}
}
