package com.roften.avilixlogger.compat.aeronautics.mixin;

import com.roften.avilixlogger.compat.aeronautics.AeronauticsCompatHooks;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.hot_air_burner.HotAirBurnerValueBehaviour")
public class HotAirBurnerValueBehaviourMixin {
    @Unique
    private AeronauticsCompatHooks.Snapshot avilixlogger$beforeHotAirValue;

    @Inject(method = "setValueSettings", at = @At("HEAD"), require = 0)
    private void avilixlogger$captureHotAirValue(Player player, @Coerce Object valueSetting, boolean ctrlDown, CallbackInfo ci) {
        try {
            this.avilixlogger$beforeHotAirValue = AeronauticsCompatHooks.captureBlockEntityBeforeFromBehaviour((Object) this, player, "hot_air_burner_value");
        } catch (Throwable ignored) {}
    }

    @Inject(method = "setValueSettings", at = @At("RETURN"), require = 0)
    private void avilixlogger$logHotAirValue(Player player, @Coerce Object valueSetting, boolean ctrlDown, CallbackInfo ci) {
        try {
            AeronauticsCompatHooks.finishBlockEntityChangeFromBehaviour((Object) this, this.avilixlogger$beforeHotAirValue, player, "hot_air_burner_value");
        } catch (Throwable ignored) {
        } finally {
            this.avilixlogger$beforeHotAirValue = null;
        }
    }
}
