package com.roften.avilixlogger.compat.backpacked.mixin;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets={"com.mrcrayfish.backpacked.common.tracker.impl.CraftingProgressTracker","com.mrcrayfish.backpacked.common.tracker.impl.UniqueCraftingProgressTracker"})
public abstract class BackpackCraftingProgressReservationMixin implements BackpackDeployerGuard {
    @Inject(method={"processCrafted","read"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$craft(CallbackInfo ci){if(CartRestoreLocks.handlerLocked(this))ci.cancel();}
}
