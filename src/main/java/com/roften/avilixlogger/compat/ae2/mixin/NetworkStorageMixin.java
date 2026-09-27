package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** One audit point for player and machine transfers through the actual grid. */
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public class NetworkStorageMixin {
    @Inject(method = "insert", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$insert(AEKey key, long requested, Actionable mode, IActionSource source,
                                      CallbackInfoReturnable<Long> result) {
        if (!Ae2Audit.insidePowered()) Ae2Audit.transfer(key, result.getReturnValue(), mode, source, true);
    }

    @Inject(method = "extract", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$extract(AEKey key, long requested, Actionable mode, IActionSource source,
                                       CallbackInfoReturnable<Long> result) {
        if (!Ae2Audit.insidePowered()) Ae2Audit.transfer(key, result.getReturnValue(), mode, source, false);
    }
}
