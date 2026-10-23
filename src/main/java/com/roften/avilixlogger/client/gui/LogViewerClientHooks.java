package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.net.C2SHelloPayload;
import com.roften.avilixlogger.net.C2SRequestDetailsPayload;
import com.roften.avilixlogger.net.S2CLogPagePayload;
import com.roften.avilixlogger.net.S2CLogDetailsPayload;
import com.roften.avilixlogger.net.S2CInspectToolPayload;
import com.roften.avilixlogger.net.S2CRollbackPreviewPayload;

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

    private static java.lang.ref.WeakReference<LogViewerScreen> OPEN_SCREEN = new java.lang.ref.WeakReference<>(null);
    private static boolean serverCreateAvailable;
    public static boolean createAvailable() { return serverCreateAvailable && com.roften.avilixlogger.core.CreateSupport.available(); }

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        OPEN_SCREEN.clear();
        LoggerActionsScreen.clear();
        LocalizedLogSearch.clear();
        serverCreateAvailable = false;
        RollbackPreviewRenderer.clear();
        // Inform server that this player supports the GUI payloads.
        var connection=Minecraft.getInstance().getConnection();
        if(connection!=null&&connection.hasChannel(C2SHelloPayload.TYPE))PacketDistributor.sendToServer(new C2SHelloPayload());
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        OPEN_SCREEN.clear();
        LoggerActionsScreen.clear();
        LocalizedLogSearch.clear();
        serverCreateAvailable = false;
        RollbackPreviewRenderer.clear();
    }

    public static void open(boolean createAvailable) { open(createAvailable, java.time.ZoneId.systemDefault().getId()); }
    public static void open(boolean createAvailable, String timeZone) {
        serverCreateAvailable = createAvailable;
        LogViewerScreen.setLogTimeZone(timeZone);
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        var screen = new LogViewerScreen();
        OPEN_SCREEN = new java.lang.ref.WeakReference<>(screen);
        mc.setScreen(screen);
        // Load using the filters/mode restored by the screen, not hard-coded defaults.
        screen.requestInitialData();
    }

    public static void acceptCart(com.roften.avilixlogger.net.S2CCartHistoryPayload payload) {
        Minecraft mc=Minecraft.getInstance();
        if(payload.kind()==com.roften.avilixlogger.net.S2CCartHistoryPayload.OPEN){
            var existing=CartHistoryScreen.owner(mc.screen);
            if(existing!=null&&!payload.cartId().isBlank()){existing.showHistory(payload.cartId());return;}
            var screen=new CartHistoryScreen(mc.screen,payload.cartId());mc.setScreen(screen);screen.load();
        }else {
            var screen=CartHistoryScreen.owner(mc.screen);if(screen==null)return;
            if(payload.kind()==com.roften.avilixlogger.net.S2CCartHistoryPayload.CONFIRM)screen.confirmation(payload.message(),payload.command());
            else screen.apply(payload);
        }
    }

    public static void acceptActionResult(com.roften.avilixlogger.net.S2CLoggerActionResultPayload payload) {
        LoggerActionsScreen.accept(payload);
    }

    public static void acceptPage(S2CLogPagePayload payload) {
        if (payload == null) return;
        var screen=OPEN_SCREEN.get();
        if (screen != null) screen.apply(payload);
    }

    public static void acceptDetails(S2CLogDetailsPayload payload) {
        if (payload == null) return;
        var cartScreen=CartHistoryScreen.owner(Minecraft.getInstance().screen);
        if (cartScreen!=null) cartScreen.acceptDetails(payload);
        var screen=OPEN_SCREEN.get();
        if (screen != null) screen.applyDetails(payload);
    }

    public static void acceptCaptureTypes(com.roften.avilixlogger.net.S2CCaptureTypesPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc != null && mc.screen instanceof CaptureTypesScreen screen) screen.apply(payload);
    }

    public static void acceptInspectTool(S2CInspectToolPayload payload) {
        if (payload == null) return;
        var screen=OPEN_SCREEN.get();
        if (screen != null) screen.applyInspectTool(payload);
    }

    public static void acceptRollbackPreview(S2CRollbackPreviewPayload payload) {
        RollbackPreviewRenderer.accept(payload);
    }
}
