package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.ChatAuditLogger;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server-side chat logging.
 *
 * We intentionally do this via mixin (require=0) to avoid hard dependencies on optional chat mods.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerChatListenerMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleChat", at = @At("HEAD"), require = 0)
    private void avilixlogger$logChat(net.minecraft.network.protocol.game.ServerboundChatPacket packet, CallbackInfo ci) {
        try {
            if (packet == null || player == null) return;
            ChatAuditLogger.packetMessage(player, packet.message());
        } catch (Throwable ignored) {
        }
    }
}
