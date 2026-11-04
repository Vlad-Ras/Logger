package com.roften.avilixlogger.compat.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2MenuCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="appeng.menu.implementations.StorageLevelEmitterMenu", remap=false)
public abstract class StorageLevelEmitterMenuMixin {
    @WrapMethod(method="setValue")
    private void avilixlogger$threshold(long value, Operation<Void> original) {
        var capture = (Ae2MenuCapture) this;
        capture.avilixlogger$beforeAction();
        try { original.call(value); }
        finally { capture.avilixlogger$afterAction(); }
    }
}
