package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import com.roften.avilixlogger.compat.ae2.Ae2CpuCancellationSource;
import appeng.menu.AEBaseMenu;
import org.spongepowered.asm.mixin.*;

@Mixin(targets="appeng.crafting.execution.CraftingCpuLogic", remap=false)
public abstract class CraftingCpuLogicMixin implements Ae2CpuCancellationSource {
    @Shadow @Final CraftingCPUCluster cluster;
    @Unique private AEBaseMenu avilixlogger$cancellationMenu;
    @Unique private boolean avilixlogger$cancellationOwned;
    public boolean avilixlogger$cancellationOwned() { return avilixlogger$cancellationOwned; }
    public boolean avilixlogger$swapCancellationOwned(boolean owned) {
        boolean previous = avilixlogger$cancellationOwned; avilixlogger$cancellationOwned = owned; return previous;
    }
    public AEBaseMenu avilixlogger$cancellationMenu() { return avilixlogger$cancellationMenu; }
    public AEBaseMenu avilixlogger$swapCancellationMenu(AEBaseMenu menu) {
        var previous = avilixlogger$cancellationMenu; avilixlogger$cancellationMenu = menu; return previous;
    }
    @WrapMethod(method="finishJob")
    private void avilixlogger$finishJob(boolean success, Operation<Void> original) {
        var capture = success ? Ae2CraftingAudit.beginCompletion(cluster) : null;
        var cancellation = success ? null : Ae2CraftingAudit.beginCpuCancellation(cluster);
        boolean previousOwner = cancellation != null && avilixlogger$swapCancellationOwned(true);
        boolean returned = false;
        try { original.call(success); returned = true; }
        finally {
            if (capture != null) capture.finish(returned);
            if (cancellation != null) {
                try { cancellation.finish(returned); }
                finally { avilixlogger$swapCancellationOwned(previousOwner); }
            }
        }
    }
}
