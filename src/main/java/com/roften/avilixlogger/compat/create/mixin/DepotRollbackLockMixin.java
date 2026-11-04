package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.logistics.depot.*;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=DepotItemHandler.class,remap=false)
public abstract class DepotRollbackLockMixin {
    @Shadow private DepotBehaviour behaviour;
    @Inject(method="insertItem",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$insert(int slot,ItemStack stack,boolean simulate,CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(stack);}
    @Inject(method="extractItem",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$extract(int slot,int amount,boolean simulate,CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(ItemStack.EMPTY);}
    @Unique private boolean avilixlogger$locked(){return CartRestoreLocks.handlerLocked(this)||CartRestoreLocks.blockLocked(behaviour.blockEntity.getLevel(),behaviour.blockEntity.getBlockPos());}
}
