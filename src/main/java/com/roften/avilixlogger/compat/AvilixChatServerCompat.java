package com.roften.avilixlogger.compat;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.core.ChatAuditLogger;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.ServerChatEvent;

/**
 * Compatibility hook for AvilixChat (and other chat routers) that cancel {@link ServerChatEvent}
 * and deliver messages via {@link ServerPlayer#sendSystemMessage} instead of vanilla chat packets.
 *
 * The packet hook is only a delayed fallback, so this event is the authoritative path for both
 * vanilla broadcasts and messages canceled/rerouted by chat mods.
 */
@EventBusSubscriber(modid = AvilixLoggerMod.MOD_ID)
public final class AvilixChatServerCompat {
    private AvilixChatServerCompat() {}

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onServerChat(ServerChatEvent event) {
        try {
            if (event == null) return;
            final ServerPlayer player = event.getPlayer();
            if (player == null) return;

            String raw = null;
            try {
                raw = event.getRawText();
            } catch (Throwable ignored) {
            }
            if (raw == null || raw.isBlank()) return;

            ChatAuditLogger.publicMessage(player, raw);
        } catch (Throwable ignored) {
        }
    }
}
