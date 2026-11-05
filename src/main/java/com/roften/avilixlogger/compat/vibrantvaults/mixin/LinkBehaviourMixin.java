package com.roften.avilixlogger.compat.vibrantvaults.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.vibrantvaults.VibrantVaultAudit;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.packagerLink.LogisticallyLinkedBehaviour", remap = false)
public abstract class LinkBehaviourMixin {
    @WrapMethod(method = "redstonePowerChanged(I)V", remap = false)
    private void avilixlogger$power(int power, Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_LINKBEHAVIOURMIXIN_POWER.enabled()) { original.call(power); return; }
        var action = VibrantVaultAudit.behaviour(this, "link-power"); boolean success = false;
        try { original.call(power); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
