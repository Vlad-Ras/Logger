package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.LogEntry;
import com.roften.avilixlogger.core.LoggerRuntime;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.Level;
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
            if (!LoggerConfig.VALUES.enabled.get()) return;
            if (!LoggerConfig.VALUES.logChat.get()) return;

            String msg = null;
            try {
                msg = packet.message();
            } catch (Throwable ignored) {
                try {
                    msg = (String) packet.getClass().getMethod("message").invoke(packet);
                } catch (Throwable ignored2) {
                    // last resort: toString
                    msg = packet.toString();
                }
            }
            if (msg == null || msg.isBlank()) return;

            Level lvl = player.level();
            if (!(lvl instanceof ServerLevel sl)) return;

            LogEntry e = new LogEntry();
            e.ts = System.currentTimeMillis();
            e.dim = sl.dimension().location().toString();
            e.type = ActionType.CHAT_MESSAGE;
            e.actorUuid = player.getUUID();
            e.actorName = player.getName().getString();
            e.x = player.getBlockX();
            e.y = player.getBlockY();
            e.z = player.getBlockZ();
            e.extra = msg;
            LoggerRuntime.storage(sl).append(e);
        } catch (Throwable ignored) {
        }
    }
}
