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
@Mixin(targets = "com.simibubi.create.content.logistics.packagePort.frogport.FrogportBlockEntity", remap = false)
public abstract class FrogportMixin {
    @WrapMethod(method = "startAnimation(Lnet/minecraft/world/item/ItemStack;Z)V", remap = false)
    private void avilixlogger$animation(ItemStack box, boolean deposit, Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_FROGPORTMIXIN_ANIMATION.enabled()) { original.call(box, deposit); return; }
        var action = VibrantVaultAudit.begin(this, "package-animation", true); boolean success = false;
        try { original.call(box, deposit); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "use(Lnet/minecraft/world/entity/player/Player;)Lnet/minecraft/world/ItemInteractionResult;", remap = false)
    private net.minecraft.world.ItemInteractionResult avilixlogger$use(net.minecraft.world.entity.player.Player player, Operation<net.minecraft.world.ItemInteractionResult> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_FROGPORTMIXIN_USE.enabled()) { return original.call(player); }
        var action = VibrantVaultAudit.begin(this, "use-port", false); boolean success = false;
        try { var result = original.call(player); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }

    @WrapMethod(method = "tick()V", remap = false)
    private void avilixlogger$tick(Operation<Void> original) {
        if (!CaptureHandlers.VIBRANTVAULTS_FROGPORTMIXIN_TICK.enabled()) { original.call(); return; }
        try (var cause = com.roften.avilixlogger.core.CauseContext.unattributed()) {
            var action = VibrantVaultAudit.tick(this); boolean success = false;
            try { original.call(); success = true; } finally { if (action != null) action.finish(!success); }
        }
    }
}
