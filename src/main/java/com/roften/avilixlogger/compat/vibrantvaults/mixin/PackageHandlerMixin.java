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
@Mixin(targets = "com.simibubi.create.content.logistics.packager.PackagerItemHandler", remap = false)
public abstract class PackageHandlerMixin {
    @WrapMethod(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$insert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        var action = VibrantVaultAudit.item(this, slot, simulate); boolean success = false;
        try { var result = original.call(slot, stack, simulate); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        var action = VibrantVaultAudit.item(this, slot, simulate); boolean success = false;
        try { var result = original.call(slot, amount, simulate); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V", remap = false)
    private void avilixlogger$set(int slot, ItemStack stack, Operation<Void> original) {
        var action = VibrantVaultAudit.item(this, slot, false); boolean success = false;
        try { original.call(slot, stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }

}
