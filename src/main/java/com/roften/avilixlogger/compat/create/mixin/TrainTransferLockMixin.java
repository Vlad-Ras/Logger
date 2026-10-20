package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.trains.entity.Train;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Stop all portal representations of a reserved train, not just one carriage tick. */
@Mixin(value=Train.class,remap=false)
public abstract class TrainTransferLockMixin {
    @Inject(method={"tick","earlyTick"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$locked(Level level,CallbackInfo ci){if(CartRestoreLocks.constructionLocked(((Train)(Object)this).id))ci.cancel();}
}
