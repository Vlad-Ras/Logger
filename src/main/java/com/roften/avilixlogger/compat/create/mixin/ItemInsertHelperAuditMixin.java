package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.core.CartAutomationHooks;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
@Mixin(value=ItemHandlerHelper.class,remap=false)
public abstract class ItemInsertHelperAuditMixin {
    @WrapOperation(method={"insertItem","insertItemStacked"},at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/items/IItemHandler;insertItem(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private static ItemStack avilixlogger$insert(IItemHandler handler,int slot,ItemStack stack,boolean simulate,Operation<ItemStack> original){
        if(CartAutomationHooks.locked(handler))return stack;
        var capture=CartAutomationHooks.item(handler,slot,simulate);boolean success=false;
        try{var result=original.call(handler,slot,stack,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
}
