package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.wrapper.SidedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value=SidedInvWrapper.class,remap=false)
public abstract class SidedHandlerAuditMixin {
    @WrapMethod(method="insertItem")
    private ItemStack avilixlogger$insert(int slot,ItemStack stack,boolean simulate,Operation<ItemStack> original){
        if(CartAutomationHooks.locked(this))return stack;
        var capture=CartAutomationHooks.item(this,slot,simulate);boolean success=false;
        try{var result=original.call(slot,stack,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="extractItem")
    private ItemStack avilixlogger$extract(int slot,int amount,boolean simulate,Operation<ItemStack> original){
        if(CartAutomationHooks.locked(this))return ItemStack.EMPTY;
        var capture=CartAutomationHooks.item(this,slot,simulate);boolean success=false;
        try{var result=original.call(slot,amount,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="setStackInSlot")
    private void avilixlogger$set(int slot,ItemStack stack,Operation<Void> original){
        if(CartAutomationHooks.locked(this))return;
        var capture=CartAutomationHooks.item(this,slot,false);boolean success=false;
        try{original.call(slot,stack);success=true;}finally{if(capture!=null)capture.finish(success);}
    }
}
