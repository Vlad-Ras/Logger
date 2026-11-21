package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import net.minecraft.world.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Loot is emitted before removal. Protect actual vanilla damage/kill overrides as well. */
@Mixin(LivingEntity.class)
public abstract class RecoveryEntityDamageLockMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$damage(CallbackInfoReturnable<Boolean> result) {
        if(RecoveryEntityReservations.locked((Entity)(Object)this))result.setReturnValue(false);
    }
    @Inject(method={"kill","die"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$death(CallbackInfo result) {if(RecoveryEntityReservations.locked((Entity)(Object)this))result.cancel();}
}
