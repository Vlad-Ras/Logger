package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vehicles override hurt; their kill method is already guarded on Entity. */
@Mixin(VehicleEntity.class)
public abstract class RecoveryVehicleDamageLockMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$damage(CallbackInfoReturnable<Boolean> result) {
        if(RecoveryEntityReservations.locked((Entity)(Object)this))result.setReturnValue(false);
    }
}
