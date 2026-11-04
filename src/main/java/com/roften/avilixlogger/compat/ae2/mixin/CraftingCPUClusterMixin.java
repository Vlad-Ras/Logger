package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.*;
import appeng.api.networking.security.IActionSource;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="appeng.me.cluster.implementations.CraftingCPUCluster", remap=false)
public abstract class CraftingCPUClusterMixin {
    @WrapMethod(method="submitJob")
    private ICraftingSubmitResult avilixlogger$submitAccepted(IGrid grid, ICraftingPlan plan, IActionSource source, ICraftingRequester requester,
                                                           Operation<ICraftingSubmitResult> original) {
        var capture = Ae2CraftingAudit.beginSubmission(plan, source, (CraftingCPUCluster)(Object)this);
        boolean returned = false;
        try {
            var result = original.call(grid, plan, source, requester);
            returned = true;
            if (capture != null) capture.finish(result, true);
            return result;
        } finally { if (capture != null) { if (!returned) capture.finishAfterFailure(); else capture.close(); } }
    }
}
