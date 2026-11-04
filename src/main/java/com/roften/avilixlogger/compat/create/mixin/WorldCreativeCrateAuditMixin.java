package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
import com.roften.avilixlogger.core.CartAutomationHooks;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;

/** World creative crate: returned quantity is real IO even when its template never changes. */
@Mixin(targets="com.simibubi.create.content.logistics.crate.BottomlessItemHandler",remap=false)
public abstract class WorldCreativeCrateAuditMixin {
    @WrapMethod(method="insertItem")
    private ItemStack avilixlogger$insert(int slot,ItemStack input,boolean simulate,Operation<ItemStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return input;
        if(simulate||!CartAutomationAudit.worldCreativeActive())return original.call(slot,input,simulate);
        ItemStack template;
        try{template=((IItemHandler)this).getStackInSlot(0).copy();}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();return original.call(slot,input,false);}
        ItemStack offered=input.copy();
        ItemStack remainder;
        try{remainder=original.call(slot,input,false);}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();throw error;}
        if(remainder==null||remainder.getCount()>offered.getCount()
                ||!remainder.isEmpty()&&!ItemStack.isSameItemSameComponents(offered,remainder)){
            CartAutomationAudit.worldCreativeUnverified();return remainder;
        }
        int accepted=offered.getCount()-remainder.getCount();
        if(accepted>0)CartAutomationAudit.worldCreativeItem(this,template,offered.copyWithCount(accepted),true);
        return remainder;
    }

    @WrapMethod(method="extractItem")
    private ItemStack avilixlogger$extract(int slot,int amount,boolean simulate,Operation<ItemStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return ItemStack.EMPTY;
        if(simulate||!CartAutomationAudit.worldCreativeActive())return original.call(slot,amount,simulate);
        ItemStack template;
        try{template=((IItemHandler)this).getStackInSlot(0).copy();}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();return original.call(slot,amount,false);}
        ItemStack result;
        try{result=original.call(slot,amount,false);}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();throw error;}
        if(result==null){CartAutomationAudit.worldCreativeUnverified();return null;}
        if(result.getCount()>amount||!result.isEmpty()&&!ItemStack.isSameItemSameComponents(template,result))
            CartAutomationAudit.worldCreativeUnverified();
        else if(!result.isEmpty())CartAutomationAudit.worldCreativeItem(this,template,result.copy(),false);
        return result;
    }
}
