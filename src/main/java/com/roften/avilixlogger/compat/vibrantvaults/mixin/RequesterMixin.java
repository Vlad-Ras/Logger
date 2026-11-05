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
@Mixin(targets = "com.simibubi.create.content.logistics.redstoneRequester.RedstoneRequesterBlockEntity", remap = false)
public abstract class RequesterMixin {
    @WrapMethod(method = "triggerRequest()V", remap = false)
    private void avilixlogger$request(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_REQUESTERMIXIN_REQUEST.enabled()) { original.call(); return; }
        var action = VibrantVaultAudit.begin(this, "request-state", false); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }

}
