package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.common.tracker.impl.CountProgressTracker;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(CountProgressTracker.class)
public abstract class BackpackCountReservationMixin implements BackpackDeployerGuard {
    @Inject(method={"increment(ILnet/minecraft/server/level/ServerPlayer;)V","read"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$count(CallbackInfo ci){if(CartRestoreLocks.handlerLocked(this))ci.cancel();}
}
