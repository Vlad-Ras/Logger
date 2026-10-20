package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.advancements.AdvancementProgress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(AdvancementProgress.class)
public abstract class ConsumptionProgressLockMixin {
    @Inject(method={"grantProgress","revokeProgress"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$progress(CallbackInfoReturnable<Boolean> cir){if(CartRestoreLocks.handlerLocked(this))cir.setReturnValue(false);}
}
