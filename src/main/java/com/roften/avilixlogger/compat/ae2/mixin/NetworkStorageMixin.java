package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe the primitive result without constructing a return callback for every storage probe. */
@Mixin(targets = "appeng.me.storage.NetworkStorage", remap = false)
public class NetworkStorageMixin {
    @ModifyReturnValue(method = "insert(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J",
            at = @At("RETURN"), require = 0, remap = false)
    private long avilixlogger$insert(long actual, AEKey key, long requested, Actionable mode, IActionSource source) {
        if (mode == Actionable.MODULATE && actual > 0 && key != null && source != null && !Ae2Audit.insidePowered()) {
            Ae2Audit.transfer(key, actual, mode, source, true);
        }
        return actual;
    }

    @ModifyReturnValue(method = "extract(Lappeng/api/stacks/AEKey;JLappeng/api/config/Actionable;Lappeng/api/networking/security/IActionSource;)J",
            at = @At("RETURN"), require = 0, remap = false)
    private long avilixlogger$extract(long actual, AEKey key, long requested, Actionable mode, IActionSource source) {
        if (mode == Actionable.MODULATE && actual > 0 && key != null && source != null && !Ae2Audit.insidePowered()) {
            Ae2Audit.transfer(key, actual, mode, source, false);
        }
        return actual;
    }
}
