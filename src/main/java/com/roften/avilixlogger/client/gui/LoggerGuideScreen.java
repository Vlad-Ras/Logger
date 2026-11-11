package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.LoggerUserGuide;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LoggerGuideScreen extends LoggerScreen {
    private final Screen parent;
    private final WrappedLogText layout = new WrappedLogText();
    private LoggerUserGuide.Topic topic;
    private int menuScroll;
    private int textScroll;

    LoggerGuideScreen(Screen parent) {
        super(Component.literal("Справка логгера"));
        this.parent = parent;
    }

    private double scale() { return LogViewerScreen.sharedControlScale(width, height); }

    private int visibleTopics() { return Math.max(1, (height - 80) / 24); }

    @Override protected void init() {
        int buttonWidth = Math.min(420, width - 20);
        int left = (width - buttonWidth) / 2;
        if (topic == null) {
            var topics = LoggerUserGuide.TOPICS;
            menuScroll = Math.min(menuScroll, Math.max(0, topics.size() - visibleTopics()));
            for (int i = 0; i < visibleTopics() && menuScroll + i < topics.size(); i++) {
                var entry = topics.get(menuScroll + i);
                addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(left, 34 + i * 24, buttonWidth, 20,
                        Component.literal(entry.title()), b -> open(entry), this::scale));
            }
        }
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(left, height - 30, buttonWidth, 20,
                Component.literal(topic == null ? "Назад" : "К разделам справки"), b -> onClose(), this::scale));
    }

    private void open(LoggerUserGuide.Topic entry) {
        topic = entry;
        textScroll = 0;
        layout.clear();
        clearWidgets();
        init();
    }

    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        LoggerGuiWidgets.beginFrame(g, width, height);
        g.drawCenteredString(font, topic == null ? title : Component.literal(topic.title()), width / 2, 12, 0xFFD700);
        if (topic == null) {
            if (visibleTopics() < LoggerUserGuide.TOPICS.size()) {
                g.drawCenteredString(font, "Прокрутите список разделов", width / 2, height - 45, 0xAAAAAA);
            }
        } else {
            var lines = layout.get(font, topic.text(), width - 28);
            int visible = Math.max(1, (height - 72) / 12);
            textScroll = Math.min(textScroll, Math.max(0, lines.size() - visible));
            g.enableScissor(12, 33, width - 12, height - 39);
            for (int i = textScroll; i < Math.min(lines.size(), textScroll + visible); i++) {
                g.drawString(font, lines.get(i), 14, 33 + (i - textScroll) * 12, 0xFFFFFF);
            }
            g.disableScissor();
            if (lines.size() > visible) {
                g.drawCenteredString(font, "Прокрутите текст", width / 2, height - 46, 0xAAAAAA);
            }
        }
        for (var widget : renderables) widget.render(g, x, y, dt);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        int delta = (int) Math.signum(dy) * 3;
        if (topic == null) {
            int next = Math.max(0, Math.min(menuScroll - delta, LoggerUserGuide.TOPICS.size() - visibleTopics()));
            if (next != menuScroll) {
                menuScroll = next;
                clearWidgets();
                init();
            }
        } else {
            textScroll = Math.max(0, textScroll - delta);
        }
        return true;
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() {
        if (topic == null) minecraft.setScreen(parent);
        else open(null);
    }
}
