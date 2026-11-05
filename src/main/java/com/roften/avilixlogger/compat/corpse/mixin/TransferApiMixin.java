package com.roften.avilixlogger.compat.corpse.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.corpse.CorpseAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = {"de.maxhenkel.corpse.gui.CorpseInventoryContainer", "de.maxhenkel.corpse.gui.CorpseAdditionalContainer"}, remap = false)
public abstract class TransferApiMixin {
    @WrapMethod(method = "transferItems()V", remap = false)
    private void avilixlogger$transfer(Operation<Void> original) {
        if (!CaptureHandlers.CORPSE_TRANSFERAPIMIXIN_TRANSFER.enabled()) { original.call(); return; }
        var action = CorpseAudit.beginMenu(this); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
