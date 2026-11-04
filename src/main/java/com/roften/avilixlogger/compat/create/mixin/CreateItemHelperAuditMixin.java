package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.core.CartAutomationHooks;
import com.simibubi.create.foundation.item.ItemHelper;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
/** The interface boundary also captures handlers that override the standard wrappers. */
@Mixin(value=ItemHelper.class,remap=false)
public abstract class CreateItemHelperAuditMixin {
    @WrapOperation(method={"extract(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;Lcom/simibubi/create/foundation/item/ItemHelper$ExtractionCountMode;IZ)Lnet/minecraft/world/item/ItemStack;","extract(Lnet/neoforged/neoforge/items/IItemHandler;Ljava/util/function/Predicate;Ljava/util/function/Function;Z)Lnet/minecraft/world/item/ItemStack;"},at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/items/IItemHandler;extractItem(IIZ)Lnet/minecraft/world/item/ItemStack;"),require=1)
    private static ItemStack avilixlogger$extract(IItemHandler handler,int slot,int amount,boolean simulate,Operation<ItemStack> original){
        if(CartAutomationHooks.locked(handler))return ItemStack.EMPTY;
        var capture=CartAutomationHooks.item(handler,slot,simulate);boolean success=false;
        try{var result=original.call(handler,slot,amount,simulate);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
}
