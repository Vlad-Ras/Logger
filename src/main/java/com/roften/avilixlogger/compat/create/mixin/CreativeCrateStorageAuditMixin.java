package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.*;
import com.simibubi.create.content.logistics.crate.CreativeCrateMountedStorage;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=CreativeCrateMountedStorage.class,remap=false)
public abstract class CreativeCrateStorageAuditMixin {
    @WrapMethod(method="insertItem")
    private ItemStack avilixlogger$void(int slot,ItemStack stack,boolean simulate,Operation<ItemStack> original){
        var ref=simulate?null:CartStorageAudit.ref(this);
        if(CartStorageAudit.locked(ref))return stack;
        if(ref==null)return original.call(slot,stack,simulate);
        var supplied=((IItemHandler)this).getStackInSlot(0).copy();var input=stack.copy();
        var transfer=CartAutomationAudit.enterMounted();ItemStack result;
        try{result=original.call(slot,stack,false);}finally{CartAutomationAudit.leaveMounted(transfer);}
        int accepted=Math.max(0,input.getCount()-result.getCount());
        CreateInfiniteStorageAudit.item(ref,slot,supplied,input.copyWithCount(accepted),true);return result;
    }
    @WrapMethod(method="extractItem")
    private ItemStack avilixlogger$supply(int slot,int amount,boolean simulate,Operation<ItemStack> original){
        var ref=simulate?null:CartStorageAudit.ref(this);
        if(CartStorageAudit.locked(ref))return ItemStack.EMPTY;
        if(ref==null)return original.call(slot,amount,simulate);
        var supplied=((IItemHandler)this).getStackInSlot(0).copy();
        var transfer=CartAutomationAudit.enterMounted();ItemStack result;
        try{result=original.call(slot,amount,false);}finally{CartAutomationAudit.leaveMounted(transfer);}
        CreateInfiniteStorageAudit.item(ref,slot,supplied,result,false);return result;
    }
}
