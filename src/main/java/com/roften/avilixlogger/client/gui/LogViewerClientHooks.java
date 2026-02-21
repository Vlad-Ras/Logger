package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.net.C2SHelloPayload;
import com.roften.avilixlogger.net.C2SRequestDetailsPayload;
import com.roften.avilixlogger.net.C2SRequestPagePayload;
import com.roften.avilixlogger.net.GuiFilters;
import com.roften.avilixlogger.net.S2CLogPagePayload;
import com.roften.avilixlogger.net.S2CLogDetailsPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Client-only glue for the optional log viewer screen.
 */
@OnlyIn(Dist.CLIENT)
public final class LogViewerClientHooks {
    private LogViewerClientHooks() {}

    private static LogViewerScreen OPEN_SCREEN;

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        // Inform server that this player supports the GUI payloads.
        PacketDistributor.sendToServer(new C2SHelloPayload());
    }

    public static void open() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        if (OPEN_SCREEN == null) OPEN_SCREEN = new LogViewerScreen();
        mc.setScreen(OPEN_SCREEN);
        // Load first page immediately.
        PacketDistributor.sendToServer(new C2SRequestPagePayload(C2SRequestPagePayload.Nav.FIRST, true, GuiFilters.DEFAULT));
    }

    public static void acceptPage(S2CLogPagePayload payload) {
        if (payload == null) return;
        if (OPEN_SCREEN != null) OPEN_SCREEN.apply(payload);
    }

    public static void acceptDetails(S2CLogDetailsPayload payload) {
        if (payload == null) return;
        if (OPEN_SCREEN != null) OPEN_SCREEN.applyDetails(payload);
    }
}
