package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.networking.crafting.ICraftingLink;
import appeng.crafting.CraftingLink;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.*;
import org.spongepowered.asm.mixin.*;

@Mixin(targets="appeng.crafting.CraftingLinkNexus", remap=false)
public abstract class CraftingLinkNexusMixin implements Ae2LinkedCancellationState {
    @Shadow private CraftingLink req;
    @Shadow private CraftingLink cpu;
    @Shadow private boolean canceled;
    @Shadow private boolean done;
    public ICraftingLink avilixlogger$requestLink() { return req; }
    public ICraftingLink avilixlogger$cpuLink() { return cpu; }
    public boolean avilixlogger$cancelled() { return canceled; }
    public boolean avilixlogger$done() { return done; }
    @WrapMethod(method="cancel")
    private void avilixlogger$linkedCancel(Operation<Void> original) {
        var capture = Ae2CraftingAudit.beginLinkedCancellation(this);
        boolean returned = false;
        try { original.call(); returned = true; }
        finally { if (capture != null) capture.finish(returned); }
    }
}
