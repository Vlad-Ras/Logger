package com.roften.avilixlogger.compat.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ae2.Ae2MenuCapture;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/** Close the original menu's scope even if the native packet throws or replaces the menu. */
@Mixin(targets = "appeng.core.network.serverbound.ConfigButtonPacket", remap = false)
public class ConfigButtonPacketMixin {
    @WrapMethod(method = "handleOnServer")
    private void avilixlogger$handle(ServerPlayer player, Operation<Void> original) {
        Ae2MenuCapture menu = player.containerMenu instanceof Ae2MenuCapture capture ? capture : null;
        if (menu == null) { original.call(player); return; }
        menu.avilixlogger$beforeAction();
        try { original.call(player); }
        finally { menu.avilixlogger$afterAction(); }
    }
}
