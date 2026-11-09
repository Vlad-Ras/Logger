package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.GuiEventVisibility;
import com.roften.avilixlogger.core.LogText;
import com.roften.avilixlogger.net.LogRow;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

final class LogRowActionsScreen extends Screen {
    private final LogViewerScreen viewer;
    private final LogRow row;

    LogRowActionsScreen(LogViewerScreen viewer, LogRow row) {
        super(Component.literal(row.aggregated() ? "Действия группы записей" : "Запись #" + row.id()));
        this.viewer = viewer;
        this.row = row;
    }

    @Override protected void init() {
        int w = Math.min(290, width - 20);
        int x = (width - w) / 2;
        String exact = "Откройте режим Raw над журналом и выберите отдельную запись.";
        var preview = button("Предпросмотр отката", x, 56, w,
                () -> LoggerActionsScreen.run(viewer, "entry_rollback", List.of(Long.toString(row.id()))));
        boolean allowed = LoggerActionsScreen.canRun("entry_rollback");
        preview.active = !row.aggregated() && allowed;
        if (!preview.active) tooltip(preview, row.aggregated() ? exact : "Для отката нужны права администратора.");

        var visibility = viewer.visibility();
        var action = row.action();
        var hideType = button(action == null ? "Скрыть тип" : "Скрыть тип · " + LogText.actionLabel(action),
                x, 82, w, () -> hide(visibility.toggle(action)));
        hideType.active = action != null && !visibility.actions().contains(action);
        if (row.aggregated()) tooltip(hideType, exact);

        String mod = row.originMod();
        String source = row.originSource();
        if (!source.isEmpty()) {
            var hideSource = button("Скрыть источник · " + GuiEventVisibility.systemOriginLabel(source), x, 108, w,
                    () -> hide(visibility.toggleSource(source)));
            hideSource.active = !visibility.sources().contains(source) && visibility.sources().size() < GuiEventVisibility.MAX_SOURCES;
            tooltip(hideSource, "Источник записи: " + source);
        } else {
            addHideModButton(x, w, visibility, mod, exact);
        }
        button("Назад к журналу", x, height - 28, w, this::onClose);
    }

    private void addHideModButton(int x, int w, GuiEventVisibility visibility, String mod, String exact) {
        var hideMod = button(mod.isEmpty() ? "Скрыть мод" : "Скрыть мод · " + mod, x, 108, w,
                () -> hide(visibility.toggleMod(mod)));
        hideMod.active = !mod.isEmpty() && !visibility.mods().contains(mod)
                && visibility.mods().size() < GuiEventVisibility.MAX_MODS;
        if (!hideMod.active) tooltip(hideMod, row.aggregated() ? exact : mod.isEmpty()
                ? "В записи нет подтверждённого источника мода."
                : "Достигнут лимит скрытых модов. Откройте настройки видимости.");
    }

    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x, y, w, 20,
                Component.literal(label), b -> action.run(), () -> LogViewerScreen.sharedControlScale(width, height)));
    }

    private static void tooltip(Button button, String text) {
        button.setTooltip(Tooltip.create(Component.literal(text)));
    }

    private void hide(GuiEventVisibility visibility) {
        onClose();
        viewer.quickHide(visibility);
    }

    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        LoggerGuiWidgets.beginFrame(g, width, height);
        g.drawCenteredString(font, title, width / 2, 15, 0xFFD700);
        g.drawCenteredString(font, "Скрытие действует только в вашем журнале", width / 2, 34, 0xD9E0EA);
        super.render(g, x, y, dt);
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(viewer); }
}
