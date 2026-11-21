package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Armor stands override both methods and can emit equipment before entity removal. */
@Mixin(ArmorStand.class)
public abstract class RecoveryArmorStandDamageLockMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$damage(CallbackInfoReturnable<Boolean> result) {
        if(RecoveryEntityReservations.locked((Entity)(Object)this))result.setReturnValue(false);
    }
    @Inject(method="kill",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$kill(CallbackInfo result) {
        if(RecoveryEntityReservations.locked((Entity)(Object)this))result.cancel();
    }
}
