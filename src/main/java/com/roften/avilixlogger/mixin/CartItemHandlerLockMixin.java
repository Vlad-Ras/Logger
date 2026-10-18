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
    private ItemStack avilixlogger$insert(int slot,ItemStack stack,boolean simulate,Operation<ItemStack> original){
        if(CartRestoreLocks.handlerLocked(this)||com.roften.avilixlogger.core.CartAutomationHooks.locked(this))return stack;
        var capture=com.roften.avilixlogger.core.CartAutomationHooks.item(this,slot,simulate);boolean success=false;
        try{var result=original.call(slot,stack,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="extractItem")
    private ItemStack avilixlogger$extract(int slot,int amount,boolean simulate,Operation<ItemStack> original){
        if(CartRestoreLocks.handlerLocked(this)||com.roften.avilixlogger.core.CartAutomationHooks.locked(this))return ItemStack.EMPTY;
        var capture=com.roften.avilixlogger.core.CartAutomationHooks.item(this,slot,simulate);boolean success=false;
        try{var result=original.call(slot,amount,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="setStackInSlot")
    private void avilixlogger$set(int slot,ItemStack stack,Operation<Void> original){
        if(CartRestoreLocks.handlerLocked(this)||com.roften.avilixlogger.core.CartAutomationHooks.locked(this))return;
        var capture=com.roften.avilixlogger.core.CartAutomationHooks.item(this,slot,false);boolean success=false;
        try{original.call(slot,stack);success=true;}finally{if(capture!=null)capture.finish(success);}
    }
}
