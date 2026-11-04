package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.roften.avilixlogger.compat.create.CreateScoreRuntime;
import net.minecraft.world.scores.PlayerTeam;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(PlayerTeam.class)
public abstract class TeamRuntimeReservationMixin {
    @Inject(method={"setDisplayName","setPlayerPrefix","setPlayerSuffix","setAllowFriendlyFire","setSeeFriendlyInvisibles","setNameTagVisibility","setDeathMessageVisibility","setCollisionRule","setColor","unpackOptions"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$lock(CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateScoreRuntime.LOCK))ci.cancel();}
}
