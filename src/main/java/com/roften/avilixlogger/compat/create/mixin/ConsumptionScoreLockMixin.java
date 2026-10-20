package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.scores.Score;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Score.class)
public abstract class ConsumptionScoreLockMixin {
    @Inject(method={"value(I)V","setLocked","display(Lnet/minecraft/network/chat/Component;)V","numberFormat(Lnet/minecraft/network/chat/numbers/NumberFormat;)V"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$score(CallbackInfo ci){if(CartRestoreLocks.handlerLocked(this))ci.cancel();}
}
