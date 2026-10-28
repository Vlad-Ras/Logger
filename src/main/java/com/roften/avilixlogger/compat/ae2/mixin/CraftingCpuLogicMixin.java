package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import org.spongepowered.asm.mixin.*;

@Mixin(targets="appeng.crafting.execution.CraftingCpuLogic", remap=false)
public abstract class CraftingCpuLogicMixin {
    @Shadow @Final CraftingCPUCluster cluster;
    @WrapMethod(method="finishJob")
    private void avilixlogger$finishJob(boolean success, Operation<Void> original) {
        var capture = success ? Ae2CraftingAudit.beginCompletion(cluster) : null;
        boolean returned = false;
        try { original.call(success); returned = true; }
        finally { if (capture != null) capture.finish(returned); }
    }
}
