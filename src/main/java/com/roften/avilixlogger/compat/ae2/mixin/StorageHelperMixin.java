package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.config.Actionable;
import appeng.api.networking.energy.IEnergySource;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEKey;
import appeng.api.storage.MEStorage;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import org.spongepowered.asm.mixin.Mixin;

/** Exception-safe powered audit; simulated probes never allocate a powered scope. */
@Mixin(targets = "appeng.api.storage.StorageHelper", remap = false)
public class StorageHelperMixin {
    @WrapMethod(method = "poweredInsert(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J")
    private static long avilixlogger$insert(IEnergySource power, MEStorage storage, AEKey key, long amount,
            IActionSource source, Actionable mode, Operation<Long> original) {
        if (mode != Actionable.MODULATE || !Ae2Audit.enabled()) return original.call(power, storage, key, amount, source, mode);
        Ae2Audit.enterPowered();
        try {
            long actual = original.call(power, storage, key, amount, source, mode);
            Ae2Audit.transfer(key, actual, mode, source, true);
            return actual;
        } finally { Ae2Audit.leavePowered(); }
    }

    @WrapMethod(method = "poweredExtraction(Lappeng/api/networking/energy/IEnergySource;Lappeng/api/storage/MEStorage;Lappeng/api/stacks/AEKey;JLappeng/api/networking/security/IActionSource;Lappeng/api/config/Actionable;)J")
    private static long avilixlogger$extract(IEnergySource power, MEStorage storage, AEKey key, long amount,
            IActionSource source, Actionable mode, Operation<Long> original) {
        if (mode != Actionable.MODULATE || !Ae2Audit.enabled()) return original.call(power, storage, key, amount, source, mode);
        Ae2Audit.enterPowered();
        try {
            long actual = original.call(power, storage, key, amount, source, mode);
            Ae2Audit.transfer(key, actual, mode, source, false);
            return actual;
        } finally { Ae2Audit.leavePowered(); }
    }
}
