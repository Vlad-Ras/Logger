package com.roften.avilixlogger.compat.vibrantvaults.mixin;
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
@Mixin(targets = "com.simibubi.create.content.logistics.vault.ItemVaultBlockEntity", remap = false)
public abstract class VaultMixin {
    @WrapMethod(method = "setController(Lnet/minecraft/core/BlockPos;)V", remap = false)
    private void avilixlogger$controller(BlockPos pos, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "controller", false); boolean success = false;
        try { original.call(pos); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeController(Z)V", remap = false)
    private void avilixlogger$split(boolean keepContents, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "split", false); boolean success = false;
        try { original.call(keepContents); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setHeight(I)V", remap = false)
    private void avilixlogger$height(int height, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "height", false); boolean success = false;
        try { original.call(height); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setWidth(I)V", remap = false)
    private void avilixlogger$width(int width, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "width", false); boolean success = false;
        try { original.call(width); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "clear", true); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "applyInventoryToBlock(Lnet/neoforged/neoforge/items/ItemStackHandler;)V", remap = false)
    private void avilixlogger$copy(net.neoforged.neoforge.items.ItemStackHandler handler, Operation<Void> original) {
        var action = VibrantVaultAudit.begin(this, "copy", true); boolean success = false;
        try { original.call(handler); success = true; }
        finally { if (action != null) action.finish(!success); }
    }

}
