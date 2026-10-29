package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.compat.ae2.Ae2CpuCancellationSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="appeng.menu.me.crafting.CraftingCPUMenu", remap=false)
public abstract class CraftingCPUMenuMixin {
    @WrapOperation(method="cancelCrafting", at=@At(value="INVOKE", target="Lappeng/me/cluster/implementations/CraftingCPUCluster;cancelJob()V"))
    private void avilixlogger$cancelJob(CraftingCPUCluster cpu, Operation<Void> original) {
        var menu = (AEBaseMenu)(Object)this;
        var source = Ae2Audit.enabled() && Ae2Audit.onServerThread(menu)
                ? (Ae2CpuCancellationSource)(Object)cpu.craftingLogic : null;
        var previous = source == null ? null : source.avilixlogger$swapCancellationMenu(menu);
        try { original.call(cpu); }
        finally { if (source != null) source.avilixlogger$swapCancellationMenu(previous); }
    }
}
