package com.roften.avilixlogger.compat.ae2.mixin;

import com.roften.avilixlogger.compat.ae2.Ae2MenuCapture;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** AE2 handles setting buttons in a separate packet, outside AEBaseMenu callbacks. */
@Mixin(targets = "appeng.core.network.serverbound.ConfigButtonPacket", remap = false)
public class ConfigButtonPacketMixin {
    @Inject(method = "handleOnServer", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$before(ServerPlayer player, CallbackInfo ci) {
        if (player.containerMenu instanceof Ae2MenuCapture menu) menu.avilixlogger$beforeAction();
    }

    @Inject(method = "handleOnServer", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$after(ServerPlayer player, CallbackInfo ci) {
        if (player.containerMenu instanceof Ae2MenuCapture menu) menu.avilixlogger$afterAction();
    }
}
