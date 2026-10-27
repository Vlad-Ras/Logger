package com.roften.avilixlogger.compat.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2MenuCapture;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="appeng.menu.implementations.PriorityMenu", remap=false)
public abstract class PriorityMenuMixin {
    @WrapMethod(method="setPriority")
    private void avilixlogger$priority(int value, Operation<Void> original) {
        var capture = (Ae2MenuCapture) this;
        capture.avilixlogger$beforeAction();
        try { original.call(value); }
        finally { capture.avilixlogger$afterAction(); }
    }
}
