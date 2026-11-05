package com.roften.avilixlogger.compat.accessories.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesCapabilityImpl", remap = false)
public abstract class CapabilityMixin {
    @WrapMethod(method = "reset(Z)V", remap = false)
    private void avilixlogger$reset(boolean loaded, Operation<Void> original) {
        if (!CaptureHandlers.ACCESSORIES_CAPABILITYMIXIN_RESET.enabled()) { original.call(loaded); return; }
        var action = AccessoriesAudit.capability(this, loaded ? "restore" : "reset"); boolean success = false;
        try { original.call(loaded); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
