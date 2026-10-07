package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Protect standard capability handlers already obtained by another machine. */
@Mixin(value={net.neoforged.neoforge.items.ItemStackHandler.class,net.neoforged.neoforge.items.wrapper.InvWrapper.class,net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class},remap=false)
public abstract class CartItemHandlerLockMixin {
    @WrapMethod(method="insertItem")
    private ItemStack avilixlogger$insert(int slot,ItemStack stack,boolean simulate,Operation<ItemStack> original){return CartRestoreLocks.handlerLocked(this)?stack:original.call(slot,stack,simulate);}
    @WrapMethod(method="extractItem")
    private ItemStack avilixlogger$extract(int slot,int amount,boolean simulate,Operation<ItemStack> original){return CartRestoreLocks.handlerLocked(this)?ItemStack.EMPTY:original.call(slot,amount,simulate);}
    @WrapMethod(method="setStackInSlot")
    private void avilixlogger$set(int slot,ItemStack stack,Operation<Void> original){if(!CartRestoreLocks.handlerLocked(this))original.call(slot,stack);}
}
