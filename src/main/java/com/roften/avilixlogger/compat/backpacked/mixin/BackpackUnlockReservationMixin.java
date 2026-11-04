package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.data.unlock.UnlockTracker;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.*;
@Mixin(UnlockTracker.class)
public abstract class BackpackUnlockReservationMixin implements BackpackDeployerGuard {
    @Inject(method="unlockBackpack",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$unlock(CallbackInfoReturnable<Boolean> ci){if(CartRestoreLocks.handlerLocked(this))ci.setReturnValue(false);}
    @Inject(method="getProgressTracker",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$progress(CallbackInfoReturnable<Optional<?>> ci){if(CartRestoreLocks.handlerLocked(this))ci.setReturnValue(Optional.empty());}
    @Inject(method="getIncompleteProgressTrackers",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$incomplete(CallbackInfoReturnable<List<?>> ci){if(CartRestoreLocks.handlerLocked(this))ci.setReturnValue(List.of());}
}
