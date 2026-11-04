package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="appeng.me.service.CraftingService", remap=false)
public abstract class CraftingServiceMixin {
    @WrapMethod(method="submitJob")
    private ICraftingSubmitResult avilixlogger$submitRefusal(ICraftingPlan plan, ICraftingRequester requester, ICraftingCPU cpu, boolean prioritizePower,
                                                          IActionSource source, Operation<ICraftingSubmitResult> original) {
        var capture = Ae2CraftingAudit.beginSubmission(plan, source, cpu);
        try {
            var result = original.call(plan, requester, cpu, prioritizePower, source);
            if (capture != null) capture.finish(result, false);
            return result;
        } finally { if (capture != null) capture.close(); }
    }
}
