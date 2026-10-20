package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

@Mixin(ServerPlayer.class)
public abstract class CartRecipientTickLockMixin {
    @Inject(method={"tick","doTick"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$freeze(CallbackInfo ci){if(CartRestoreLocks.playerLocked((ServerPlayer)(Object)this))ci.cancel();}
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$damage(CallbackInfoReturnable<Boolean> ci){if(CartRestoreLocks.playerLocked((ServerPlayer)(Object)this))ci.setReturnValue(false);}
}
