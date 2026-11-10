package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.net.C2SHelloPayload;
import com.roften.avilixlogger.net.C2SRequestDetailsPayload;
import com.roften.avilixlogger.net.ClientReportPayloads;
import com.roften.avilixlogger.net.ClientReportViewPayloads;
import com.roften.avilixlogger.net.S2CLogPagePayload;
import com.roften.avilixlogger.net.S2CLogDetailsPayload;
import com.roften.avilixlogger.net.S2CInspectToolPayload;
import com.roften.avilixlogger.net.S2CRollbackPreviewPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Client-only glue for the optional log viewer screen.
 */
@OnlyIn(Dist.CLIENT)
public final class LogViewerClientHooks {
    private LogViewerClientHooks() {}

    private static java.lang.ref.WeakReference<LogViewerScreen> OPEN_SCREEN = new java.lang.ref.WeakReference<>(null);
    private static boolean serverCreateAvailable;
    private static com.roften.avilixlogger.net.S2CCaptureHealthPayload captureHealth;
    static com.roften.avilixlogger.net.S2CCaptureHealthPayload captureHealth() { return captureHealth; }
    public static void acceptCaptureHealth(com.roften.avilixlogger.net.S2CCaptureHealthPayload payload) {
        captureHealth = payload;
        var screen = OPEN_SCREEN.get();
        if (screen != null) screen.updateCaptureHealth();
    }
    public static boolean createAvailable() { return serverCreateAvailable && com.roften.avilixlogger.core.CreateSupport.available(); }

    public static void onLogin(ClientPlayerNetworkEvent.LoggingIn e) {
        captureHealth = null;
        OPEN_SCREEN.clear();
        teleportOwner.clear();teleportRequest=0;
        LoggerActionsScreen.clear();
        LocalizedLogSearch.clear();
        serverCreateAvailable = false;
        RollbackPreviewRenderer.clear();
        // Inform server that this player supports the GUI payloads.
        var connection=Minecraft.getInstance().getConnection();
        if(connection!=null&&connection.hasChannel(C2SHelloPayload.TYPE))PacketDistributor.sendToServer(new C2SHelloPayload());
    }

    public static void acceptClientReportRequest(ClientReportPayloads.Request request, IPayloadContext context) {
        Minecraft minecraft = Minecraft.getInstance();
        var connection = minecraft.getConnection();
        if (connection == null || connection.getConnection() != context.connection()
                || !connection.hasChannel(ClientReportPayloads.Report.TYPE)) return;

        var mods = ModList.get().getMods().stream()
                .map(mod -> new ClientReportPayloads.Mod(mod.getModId(), mod.getVersion().toString()))
                .sorted(java.util.Comparator.comparing(ClientReportPayloads.Mod::id)).toList();
        var report = ClientReportPayloads.collect(request.id(), mods, minecraft.getResourcePackRepository().getSelectedIds());
        context.reply(report);
    }

    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut e) {
        captureHealth = null;
        OPEN_SCREEN.clear();
        teleportOwner.clear();teleportRequest=0;
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

    private static long teleportSequence,teleportRequest;
    private static java.lang.ref.WeakReference<LogViewerScreen> teleportOwner=new java.lang.ref.WeakReference<>(null);
    static void teleport(LogViewerScreen owner,long entryId) {
        var connection=Minecraft.getInstance().getConnection();
        if(connection==null||!connection.hasChannel(com.roften.avilixlogger.net.C2SEntryTeleportPayload.TYPE)) {
            owner.applyEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(0,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,"Для перехода из строки обновите сервер и админклиент до 2.1.29."));return;
        }
        var old=teleportOwner.get();if(old!=null)old.applyEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(teleportRequest,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.FAILURE,"Переход заменён новым запросом."));
        teleportOwner=new java.lang.ref.WeakReference<>(owner);teleportRequest=++teleportSequence;
        owner.applyEntryTeleport(new com.roften.avilixlogger.net.S2CEntryTeleportPayload(teleportRequest,com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.PREPARING,"Подготавливаю переход к выбранной записи…"));
        PacketDistributor.sendToServer(new com.roften.avilixlogger.net.C2SEntryTeleportPayload(teleportRequest,entryId));
    }
    public static void acceptEntryTeleport(com.roften.avilixlogger.net.S2CEntryTeleportPayload payload) {
        var owner=teleportOwner.get();if(owner==null||payload.request()!=teleportRequest)return;
        owner.applyEntryTeleport(payload);
        if(payload.state()!=com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.PREPARING)teleportOwner.clear();
        if(payload.state()==com.roften.avilixlogger.net.S2CEntryTeleportPayload.State.SUCCESS&&Minecraft.getInstance().screen==owner)Minecraft.getInstance().setScreen(null);
    }

    public static void acceptActionResult(com.roften.avilixlogger.net.S2CLoggerActionResultPayload payload) {
        LoggerActionsScreen.accept(payload);
    }

    public static void acceptClientReportView(ClientReportViewPayloads.View view) {
        if (Minecraft.getInstance().screen instanceof ClientReportScreen screen) screen.accept(view);
    }

    static void openModule(net.minecraft.client.gui.screens.Screen parent,int preset) {
        var screen=LogViewerScreen.forModule(parent,preset);
        OPEN_SCREEN=new java.lang.ref.WeakReference<>(screen);
        Minecraft.getInstance().setScreen(screen);screen.requestInitialData();
    }
    static void resume(net.minecraft.client.gui.screens.Screen screen) {
        if(screen instanceof LogViewerScreen journal){OPEN_SCREEN=new java.lang.ref.WeakReference<>(journal);journal.requestInitialData();}
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
