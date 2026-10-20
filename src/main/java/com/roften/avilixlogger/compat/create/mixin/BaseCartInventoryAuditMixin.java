package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Includes a chest minecart's attached inventory, without polling it on ticks. */
@Mixin(AbstractMinecartContainer.class)
public abstract class BaseCartInventoryAuditMixin {
    @WrapMethod(method="setItem")
    private void avilixlogger$set(int slot,ItemStack value,Operation<Void> original){
        if(CartAutomationHooks.baseLocked(this))return;
        var capture=CartAutomationHooks.baseItem((Container)this,slot);boolean success=false;
        try{original.call(slot,value);success=true;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="removeItem")
    private ItemStack avilixlogger$remove(int slot,int count,Operation<ItemStack> original){
        if(CartAutomationHooks.baseLocked(this))return ItemStack.EMPTY;
        var capture=CartAutomationHooks.baseItem((Container)this,slot);boolean success=false;
        try{var result=original.call(slot,count);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="removeItemNoUpdate")
    private ItemStack avilixlogger$removeAll(int slot,Operation<ItemStack> original){
        if(CartAutomationHooks.baseLocked(this))return ItemStack.EMPTY;
        var capture=CartAutomationHooks.baseItem((Container)this,slot);boolean success=false;
        try{var result=original.call(slot);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
}
