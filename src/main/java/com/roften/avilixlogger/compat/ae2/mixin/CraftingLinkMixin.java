package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.networking.crafting.ICraftingLink;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2CraftingAudit;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets="appeng.crafting.CraftingLink", remap=false)
public abstract class CraftingLinkMixin {
    @WrapMethod(method="cancel")
    private void avilixlogger$linkCancel(Operation<Void> original) {
        var capture = Ae2CraftingAudit.beginLinkCancellation((ICraftingLink)(Object)this);
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { if (capture != null) capture.finish(returned); }
    }
}
