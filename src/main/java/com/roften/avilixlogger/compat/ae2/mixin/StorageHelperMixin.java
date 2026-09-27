package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Includes portable/ME-chest operations; avoids duplicating their underlying network call. */
@Mixin(targets = "appeng.api.storage.StorageHelper", remap = false)
public class StorageHelperMixin {
    @Inject(method = "poweredInsert(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J",
            at = @At("HEAD"), require = 0, remap = false)
    private static void avilixlogger$beforeInsert(IEnergySource power, MEStorage storage, AEKey key, long amount,
                                                   IActionSource source, Actionable mode, CallbackInfoReturnable<Long> cir) {
        Ae2Audit.enterPowered();
    }

    @Inject(method = "poweredInsert(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J",
            at = @At("RETURN"), require = 0, remap = false)
    private static void avilixlogger$afterInsert(IEnergySource power, MEStorage storage, AEKey key, long amount,
                                                  IActionSource source, Actionable mode, CallbackInfoReturnable<Long> cir) {
        try { Ae2Audit.transfer(key, cir.getReturnValue(), mode, source, true); }
        finally { Ae2Audit.leavePowered(); }
    }

    @Inject(method = "poweredExtraction(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J",
            at = @At("HEAD"), require = 0, remap = false)
    private static void avilixlogger$beforeExtract(IEnergySource power, MEStorage storage, AEKey key, long amount,
                                                    IActionSource source, Actionable mode, CallbackInfoReturnable<Long> cir) {
        Ae2Audit.enterPowered();
    }

    @Inject(method = "poweredExtraction(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J",
            at = @At("RETURN"), require = 0, remap = false)
    private static void avilixlogger$afterExtract(IEnergySource power, MEStorage storage, AEKey key, long amount,
                                                   IActionSource source, Actionable mode, CallbackInfoReturnable<Long> cir) {
        try { Ae2Audit.transfer(key, cir.getReturnValue(), mode, source, false); }
        finally { Ae2Audit.leavePowered(); }
    }
}
