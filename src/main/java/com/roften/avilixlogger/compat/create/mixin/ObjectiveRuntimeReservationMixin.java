package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.roften.avilixlogger.compat.create.CreateScoreRuntime;
import net.minecraft.world.scores.Objective;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Objective.class)
public abstract class ObjectiveRuntimeReservationMixin {
    @Inject(method={"setDisplayName","setRenderType","setDisplayAutoUpdate","setNumberFormat"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$lock(CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateScoreRuntime.LOCK))ci.cancel();}
}
