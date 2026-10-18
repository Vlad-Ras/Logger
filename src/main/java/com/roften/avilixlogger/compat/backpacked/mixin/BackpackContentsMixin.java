package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.container.UnlockableContainer;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(UnlockableContainer.class)
public abstract class BackpackContentsMixin {
    // The dock fills its inventory after the constructor has returned.
    @Inject(method={"copyFrom","load"},at=@At("RETURN"))
    private void avilixlogger$loaded(CallbackInfo ci){BackpackAudit.initialize((Container)this);}
    @Inject(method="removeItemNoUpdate",at=@At("RETURN"))
    private void avilixlogger$noUpdate(CallbackInfoReturnable<?> ci){BackpackAudit.changed((Container)this);}
}
