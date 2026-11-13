package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.ClientReportAlertPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;

final class ClientReportAlertsScreen extends LoggerScreen {
    private final Screen parent;
    private final WrappedLogText layout = new WrappedLogText();
    private List<Component> lines = List.of(Component.literal("Загрузка настройки…"));
    private Button toggle;
    private boolean enabled;
    private boolean loaded;
    private int scroll;

    ClientReportAlertsScreen(Screen parent) {
        super(Component.literal("Уведомления об отчётах"));
        this.parent = parent;
    }

    static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(ClientReportAlertPayloads.Query.TYPE)
                && connection.hasChannel(ClientReportAlertPayloads.View.TYPE);
    }

    @Override protected void init() {
        int panel = Math.min(450, width - 20);
        int left = (width - panel) / 2;
        toggle = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(left, 32, panel, 20,
                Component.empty(), b -> send(enabled ? ClientReportAlertPayloads.Action.DISABLE : ClientReportAlertPayloads.Action.ENABLE),
                () -> LogViewerScreen.sharedControlScale(width, height)));
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(left, height - 30, panel, 20,
                Component.literal("Назад"), b -> onClose(), () -> LogViewerScreen.sharedControlScale(width, height)));
        updateToggle();
        if (!loaded) send(ClientReportAlertPayloads.Action.READ);
    }

    void accept(ClientReportAlertPayloads.View view) {
        enabled = view.enabled();
        loaded = true;
        lines = view.alerts().isEmpty()
                ? List.of(Component.literal(enabled ? "Нет недавних совпадений." : "Уведомления выключены."))
                : view.alerts().stream().map(text -> (Component) Component.literal(text)).toList();
        scroll = 0;
        updateToggle();
    }

    private void updateToggle() {
        toggle.active = loaded;
        toggle.setMessage(Component.literal(enabled ? "Выключить мои уведомления" : "Включить мои уведомления"));
    }

    private void send(ClientReportAlertPayloads.Action action) {
        if (available()) PacketDistributor.sendToServer(new ClientReportAlertPayloads.Query(action));
    }

    @Override public void render(GuiGraphics g, int x, int y, float delta) {
        LoggerGuiWidgets.beginFrame(g, width, height, false);
        g.drawCenteredString(font, title, width / 2, 13, 0xFFD700);
        int panel = Math.min(450, width - 20);
        int left = (width - panel) / 2;
        int bottom = height - 38;
        LoggerGuiWidgets.journalBackground(g, left, 60, left + panel, bottom);
        var wrapped = layout.get(font, lines, panel - 16);
        int visible = Math.max(1, (bottom - 64) / 12);
        scroll = Math.min(scroll, Math.max(0, wrapped.size() - visible));
        for (int i = 0; i < visible && i + scroll < wrapped.size(); i++) {
            g.drawString(font, wrapped.get(i + scroll), left + 8, 64 + i * 12, 0xE7EDF5);
        }
        super.render(g, x, y, delta);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
        return true;
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float delta) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
