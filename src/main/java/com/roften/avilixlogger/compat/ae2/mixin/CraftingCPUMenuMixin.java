package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.menu.AEBaseMenu;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets="appeng.menu.me.crafting.CraftingCPUMenu", remap=false)
public abstract class CraftingCPUMenuMixin {
    @WrapOperation(method="cancelCrafting", at=@At(value="INVOKE", target="Lappeng/me/cluster/implementations/CraftingCPUCluster;cancelJob()V"))
    private void avilixlogger$cancelJob(CraftingCPUCluster cpu, Operation<Void> original) {
        var capture = Ae2CraftingAudit.beginCancellation((AEBaseMenu)(Object)this, cpu);
        try { original.call(cpu); } finally { if (capture != null) capture.finish(); }
    }
}
