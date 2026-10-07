package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartStorageAudit;
import com.simibubi.create.api.contraption.storage.item.WrapperMountedItemStorage;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = WrapperMountedItemStorage.class, remap = false)
public abstract class MountedItemStorageAuditMixin {
    @WrapMethod(method = "insertItem")
    private ItemStack avilixlogger$insert(int slot, ItemStack stack, boolean simulate, Operation<ItemStack> original) {
        var ref = simulate ? null : CartStorageAudit.ref(this);
        if (CartStorageAudit.locked(ref)) return stack;
        if (ref == null) return original.call(slot, stack, simulate);
        var h = (IItemHandler)this; ItemStack before = h.getStackInSlot(slot).copy();
        ItemStack result = original.call(slot, stack, false);
        CartStorageAudit.changed(ref, slot, before, h.getStackInSlot(slot)); return result;
    }
    @WrapMethod(method = "extractItem")
    private ItemStack avilixlogger$extract(int slot, int amount, boolean simulate, Operation<ItemStack> original) {
        var ref = simulate ? null : CartStorageAudit.ref(this);
        if (CartStorageAudit.locked(ref)) return ItemStack.EMPTY;
        if (ref == null) return original.call(slot, amount, simulate);
        var h = (IItemHandler)this; ItemStack before = h.getStackInSlot(slot).copy();
        ItemStack result = original.call(slot, amount, false);
        CartStorageAudit.changed(ref, slot, before, h.getStackInSlot(slot)); return result;
    }
    @WrapMethod(method = "setStackInSlot")
    private void avilixlogger$set(int slot, ItemStack stack, Operation<Void> original) {
        var ref = CartStorageAudit.ref(this);
        if (CartStorageAudit.locked(ref)) return;
        if (ref == null) { original.call(slot, stack); return; }
        var h = (IItemHandler)this; ItemStack before = h.getStackInSlot(slot).copy();
        original.call(slot, stack); CartStorageAudit.changed(ref, slot, before, h.getStackInSlot(slot));
    }
}
