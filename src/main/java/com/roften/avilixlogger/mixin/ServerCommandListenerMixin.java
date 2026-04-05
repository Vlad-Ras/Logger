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

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCommandListenerMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), require = 0)
    private void avilixlogger$logCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, CallbackInfo ci) {
        try {
            if (packet == null || player == null) return;
            if (!LoggerConfig.VALUES.enabled.get() || !LoggerConfig.VALUES.logChat.get()) return;
            String msg = null;
            try {
                msg = (String) packet.getClass().getMethod("command").invoke(packet);
            } catch (Throwable ignored) {
                try {
                    msg = (String) packet.getClass().getMethod("message").invoke(packet);
                } catch (Throwable ignored2) {
                    msg = packet.toString();
                }
            }
            if (msg == null || msg.isBlank()) return;
            if (!msg.startsWith("/")) msg = "/" + msg;

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
