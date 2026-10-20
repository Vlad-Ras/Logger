package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.logistics.depot.DepotBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
@Mixin(value=DepotBehaviour.class,remap=false)
public abstract class DepotBehaviourRollbackMixin {
    @Unique private boolean avilixlogger$locked(){var self=(DepotBehaviour)(Object)this;return CartRestoreLocks.blockLocked(self.blockEntity.getLevel(),self.blockEntity.getBlockPos());}
    @Inject(method="insert",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$insert(TransportedItemStack item,boolean simulate,CallbackInfoReturnable<ItemStack> ci){if(avilixlogger$locked())ci.setReturnValue(item.stack);}
    @Inject(method={"clearContent","setHeldItem","removeHeldItem","setCenteredHeldItem"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$mutation(CallbackInfo ci){if(avilixlogger$locked())ci.cancel();}
}
