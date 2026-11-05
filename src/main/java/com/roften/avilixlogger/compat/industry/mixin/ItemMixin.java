package com.roften.avilixlogger.compat.industry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "net.neoforged.neoforge.items.ItemStackHandler", remap = false)
public abstract class ItemMixin {
    @WrapMethod(method = "insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$insert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        if (!CaptureHandlers.INDUSTRY_ITEMMIXIN_INSERT.enabled()) { return original.call(slot, stack, simulate); }
        var action = IndustrialAudit.item(this, simulate); boolean success = false;
        try { var result = original.call(slot, stack, simulate); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        if (!CaptureHandlers.INDUSTRY_ITEMMIXIN_EXTRACT.enabled()) { return original.call(slot, amount, simulate); }
        var action = IndustrialAudit.item(this, simulate); boolean success = false;
        try { var result = original.call(slot, amount, simulate); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setStackInSlot(ILnet/minecraft/world/item/ItemStack;)V", remap = false)
    private void avilixlogger$set(int slot, ItemStack stack, Operation<Void> original) {
        if (!CaptureHandlers.INDUSTRY_ITEMMIXIN_SET.enabled()) { original.call(slot, stack); return; }
        var action = IndustrialAudit.item(this, false); boolean success = false;
        try { original.call(slot, stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)V", remap = false)
    private void avilixlogger$read(HolderLookup.Provider provider, CompoundTag tag, Operation<Void> original) {
        if (!CaptureHandlers.INDUSTRY_ITEMMIXIN_READ.enabled()) { original.call(provider, tag); return; }
        var action = IndustrialAudit.item(this, false); boolean success = false;
        try { original.call(provider, tag); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setSize(I)V", remap = false)
    private void avilixlogger$resize(int size, Operation<Void> original) {
        if (!CaptureHandlers.INDUSTRY_ITEMMIXIN_RESIZE.enabled()) { original.call(size); return; }
        var action = IndustrialAudit.item(this, false); boolean success = false;
        try { original.call(size); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
