package com.roften.avilixlogger.compat.vibrantvaults.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import java.util.List;
import com.roften.avilixlogger.compat.vibrantvaults.VibrantVaultAudit;
@Pseudo
@Mixin(targets = "com.simibubi.create.content.logistics.packagePort.PackagePortBlockEntity", remap = false)
public abstract class PortMixin {
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PORTMIXIN_CLEAR.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "clear", true); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "destroy()V", remap = false)
    private void avilixlogger$destroy(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PORTMIXIN_DESTROY.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "destroy", true); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }

}
