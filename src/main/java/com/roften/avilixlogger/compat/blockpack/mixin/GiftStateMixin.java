package com.roften.avilixlogger.compat.blockpack.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="com.blockfactory.blockpack.event.GiftSavedData",remap=false)
public abstract class GiftStateMixin {
    @Shadow private boolean giftEnabled;
    @WrapMethod(method="setGiftEnabled(Z)V",remap=false)
    private void avilixlogger$setGift(boolean value,Operation<Void> original){
        if (!CaptureHandlers.BLOCKPACK_GIFTSTATEMIXIN_SETGIFT.enabled()) { original.call(value); return; }
        boolean before=giftEnabled;original.call(value);BlockPackGiftAudit.changed(before,giftEnabled);
    }
}
