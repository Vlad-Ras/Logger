package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CartStorageAudit;
import com.roften.avilixlogger.compat.create.CreateCartAudit;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = CarriageContraption.class, remap = false)
public abstract class CarriageStorageBindingMixin {
    @Inject(method = "swapStorageAfterAssembly", at = @At("RETURN"), require = 1)
    private void avilixlogger$proxy(CarriageContraptionEntity entity, CallbackInfo ci) {
        if (!entity.level().isClientSide && CreateCartAudit.enabled()) CartStorageAudit.ensureBound(entity);
    }
}
