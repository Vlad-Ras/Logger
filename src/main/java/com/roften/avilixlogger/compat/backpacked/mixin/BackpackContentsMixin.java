package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.container.UnlockableContainer;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(UnlockableContainer.class)
public abstract class BackpackContentsMixin implements BackpackDeployerGuard {
    @org.spongepowered.asm.mixin.Unique private boolean avilixlogger$locked(){
        if(!com.roften.avilixlogger.core.CartAuditContext.rollbackActive||com.roften.avilixlogger.core.CartAuditContext.restoring())return false;
        if(CartRestoreLocks.containerLocked((Container)this))return true;
        var ref=BackpackAudit.ref((Container)this);
        return ref!=null&&(ref.holder()!=null&&CartRestoreLocks.playerLocked(ref.holder())||CartRestoreLocks.blockLocked(ref.level(),ref.pos()));
    }
    @Inject(method={"setItem","clearContent","copyFrom","load"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$write(CallbackInfo ci){if(avilixlogger$locked())ci.cancel();}
    @Inject(method={"removeItem","removeItemNoUpdate"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$remove(CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(ItemStack.EMPTY);}
    @Inject(method="addItem",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$add(ItemStack incoming,CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(incoming);}
    @Inject(method={"canPlaceItem","canTakeItem"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$accept(CallbackInfoReturnable<Boolean> ci){if(avilixlogger$locked())ci.setReturnValue(false);}
    // The dock fills its inventory after the constructor has returned.
    @Inject(method={"copyFrom","load"},at=@At("RETURN"))
    private void avilixlogger$loaded(CallbackInfo ci){BackpackAudit.initialize((Container)this);}
    @Inject(method="removeItemNoUpdate",at=@At("RETURN"))
    private void avilixlogger$noUpdate(CallbackInfoReturnable<?> ci){BackpackAudit.changed((Container)this);}
}
