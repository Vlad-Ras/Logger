package com.roften.avilixlogger.compat.backpacked.mixin;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(targets="com.mrcrayfish.backpacked.common.challenge.impl.ExploreBiomeChallenge$Tracker")
public abstract class BackpackExploreReservationMixin implements BackpackDeployerGuard {
    @Inject(method={"explore","read"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$explore(CallbackInfo ci){if(CartRestoreLocks.handlerLocked(this))ci.cancel();}
}
