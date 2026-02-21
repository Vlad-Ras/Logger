package com.roften.avilixlogger.net;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Small runtime hooks for keeping GUI capability state clean. */
public final class LoggerNetworkHooks {
    private LoggerNetworkHooks() {}

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.getEntity() instanceof net.minecraft.server.level.ServerPlayer sp) {
            LoggerNetwork.clear(sp);
        }
    }
}
