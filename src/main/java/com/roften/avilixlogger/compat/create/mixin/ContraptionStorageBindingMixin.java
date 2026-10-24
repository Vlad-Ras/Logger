package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.compat.create.CartStorageAudit;
import com.roften.avilixlogger.compat.create.CreateCartAudit;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = AbstractContraptionEntity.class, remap = false)
public abstract class ContraptionStorageBindingMixin {
    @Inject(method = "setContraption", at = @At("RETURN"), require = 1)
    private void avilixlogger$bind(Contraption contraption, CallbackInfo ci) {
        if(contraption!=null && contraption instanceof com.roften.avilixlogger.compat.create.ContraptionIdentity identity
                && identity.avilixlogger$identity()==null) identity.avilixlogger$identity(((AbstractContraptionEntity)(Object)this).getUUID());
        avilixlogger$refresh(true);
    }
    @Inject(method = "tick", at = @At("HEAD"), require = 1)
    private void avilixlogger$bindings(CallbackInfo ci) { avilixlogger$refresh(false); }
    private void avilixlogger$refresh(boolean initial) {
        var entity = (AbstractContraptionEntity)(Object)this;
        if (!entity.level().isClientSide && (initial || com.roften.avilixlogger.core.CartAuditContext.rollbackActive || com.roften.avilixlogger.compat.create.CreateCapturePolicy.cargo(entity)) && CreateCartAudit.tickEnabled()) CartStorageAudit.ensureBound(entity);
    }
}
