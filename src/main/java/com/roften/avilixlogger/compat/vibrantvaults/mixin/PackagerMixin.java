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
@Mixin(targets = "com.simibubi.create.content.logistics.packager.PackagerBlockEntity", remap = false)
public abstract class PackagerMixin {
    @WrapMethod(method = "unwrapBox(Lnet/minecraft/world/item/ItemStack;Z)Z", remap = false)
    private boolean avilixlogger$unwrap(ItemStack box, boolean simulate, Operation<Boolean> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_UNWRAP.enabled()) { return original.call(box, simulate); }
        var action = simulate ? null : VibrantVaultAudit.begin(this, "unwrap", true); boolean success = false;
        try { var result = original.call(box, simulate); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "attemptToSend(Ljava/util/List;)V", remap = false)
    private void avilixlogger$package(List<?> requests, Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_PACKAGE.enabled()) { original.call(requests); return; }
        var action = VibrantVaultAudit.begin(this, "package", true); boolean success = false;
        try { original.call(requests); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_CLEAR.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "clear", true); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "destroy()V", remap = false)
    private void avilixlogger$destroy(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_DESTROY.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "destroy", true); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "updateSignAddress()V", remap = false)
    private void avilixlogger$address(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_ADDRESS.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "address", false); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }

    @WrapMethod(method = "tick()V", remap = false)
    private void avilixlogger$tick(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_PACKAGERMIXIN_TICK.enabled()) { original.call(); return; }
        try (var cause = com.roften.avilixlogger.core.CauseContext.unattributed()) {
            var action = VibrantVaultAudit.tick(this); boolean success = false;
            try { original.call(); success = true; } finally { if (action != null) action.finish(!success); }
        }
    }
}
