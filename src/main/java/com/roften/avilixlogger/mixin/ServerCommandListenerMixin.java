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
 * Logs slash commands typed by players (e.g. /msg, /tell, /w, /opm).
 * Uses CHAT_MESSAGE so the existing chat log UI/filters keep working unchanged.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCommandListenerMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "handleChatCommand", at = @At("HEAD"), require = 0)
    private void avilixlogger$logChatCommand(net.minecraft.network.protocol.game.ServerboundChatCommandPacket packet, CallbackInfo ci) {
        try {
            if (packet == null || player == null) return;
            if (!LoggerConfig.VALUES.enabled.get()) return;
            if (!LoggerConfig.VALUES.logChat.get()) return;

            String cmd = null;
            try {
                cmd = packet.command();
            } catch (Throwable ignored) {
                try {
                    cmd = (String) packet.getClass().getMethod("command").invoke(packet);
                } catch (Throwable ignored2) {
                    cmd = packet.toString();
                }
            }
            if (cmd == null) return;
            cmd = cmd.trim();
            if (cmd.isBlank()) return;

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
            e.extra = "/" + cmd;
            LoggerRuntime.storage(sl).append(e);
        } catch (Throwable ignored) {
        }
    }
}
