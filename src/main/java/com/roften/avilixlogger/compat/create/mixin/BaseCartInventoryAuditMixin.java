package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartStorageAudit;
import net.minecraft.world.Container;
import net.minecraft.world.entity.vehicle.AbstractMinecartContainer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;

/** Includes a chest minecart's attached inventory, without polling it on ticks. */
@Mixin(AbstractMinecartContainer.class)
public abstract class BaseCartInventoryAuditMixin {
    @WrapMethod(method="setItem")
    private void avilixlogger$set(int slot,ItemStack value,Operation<Void> original){
        var ref=CartStorageAudit.ref(this);if(CartStorageAudit.locked(ref))return;
        if(ref==null){original.call(slot,value);return;}
        var inventory=(Container)this;var before=inventory.getItem(slot).copy();original.call(slot,value);CartStorageAudit.changed(ref,slot,before,inventory.getItem(slot));
    }
    @WrapMethod(method="removeItem")
    private ItemStack avilixlogger$remove(int slot,int count,Operation<ItemStack> original){
        var ref=CartStorageAudit.ref(this);if(CartStorageAudit.locked(ref))return ItemStack.EMPTY;
        if(ref==null)return original.call(slot,count);
        var inventory=(Container)this;var before=inventory.getItem(slot).copy();var result=original.call(slot,count);CartStorageAudit.changed(ref,slot,before,inventory.getItem(slot));return result;
    }
    @WrapMethod(method="removeItemNoUpdate")
    private ItemStack avilixlogger$removeAll(int slot,Operation<ItemStack> original){
        var ref=CartStorageAudit.ref(this);if(CartStorageAudit.locked(ref))return ItemStack.EMPTY;
        if(ref==null)return original.call(slot);
        var inventory=(Container)this;var before=inventory.getItem(slot).copy();var result=original.call(slot);CartStorageAudit.changed(ref,slot,before,inventory.getItem(slot));return result;
    }
}
