package com.roften.avilixlogger.client;

import com.roften.avilixlogger.client.gui.LogViewerClientHooks;
import com.roften.avilixlogger.client.gui.LogViewerKeybinds;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only bootstrap.
 *
 * We register runtime listeners explicitly instead of using @EventBusSubscriber(bus=...),
 * because the 'bus' parameter is deprecated in the user's NeoForge version and emits warnings.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientBootstrap {
    private ClientBootstrap() {}

    public static void init(IEventBus modBus) {
        // Keybinds
        modBus.addListener(LogViewerKeybinds::registerKeyMappings);

        // Runtime (game) bus listeners
        NeoForge.EVENT_BUS.addListener(LogViewerClientHooks::onLogin);
        NeoForge.EVENT_BUS.addListener(LogViewerKeybinds::onClientTick);
    }
}
