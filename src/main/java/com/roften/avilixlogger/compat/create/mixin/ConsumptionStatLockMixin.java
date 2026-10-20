package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.stats.*;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(StatsCounter.class)
public abstract class ConsumptionStatLockMixin {
    @Inject(method="setValue",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$stat(Player p,Stat<?> stat,int value,CallbackInfo ci){if(CartRestoreLocks.playerLocked(p))ci.cancel();}
}
