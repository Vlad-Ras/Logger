package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.ActionType;
import com.roften.avilixlogger.core.GuiEventVisibility;
import com.roften.avilixlogger.core.LogText;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class GuiVisibilityScreen extends Screen {
    private record Choice(String label, String key, int preset, ActionType action) {}

    private final List<Choice> categories = GuiEventVisibility.supportedCategories().stream()
            .map(category -> new Choice(CaptureTypesScreen.categoryLabel(category.preset()), "", category.preset(), null))
            .toList();
    private static final List<Choice> ACTIONS = java.util.Arrays.stream(ActionType.values())
            .map(action -> new Choice(LogText.actionLabel(action), action.name(), 0, action))
            .toList();

    private final Screen parent;
    private final LogViewerScreen viewer;
    private final List<Button> rows = new ArrayList<>();
    private List<Choice> matches = List.of();
    private GuiEventVisibility selection;
    private int tab;
    private int scroll;
    private String needle = "";

    GuiVisibilityScreen(Screen parent, LogViewerScreen viewer) {
        super(Component.literal("Видимость событий в GUI"));
        this.parent = parent;
        this.viewer = viewer;
        this.selection = viewer.visibility();
    }

    private Button button(String title, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x, y, w, 18,
                Component.literal(title), b -> action.run(), () -> LogViewerScreen.sharedControlScale(width, height)));
    }

    @Override protected void init() {
        rows.clear();
        int w = Math.min(450, width - 20);
        int x = (width - w) / 2;
        int tabWidth = (w - 12) / 3;
        button("Категории", x, 35, tabWidth, () -> show(0)).active = tab != 0;
        button("Типы событий", x + tabWidth + 6, 35, tabWidth, () -> show(1)).active = tab != 1;
        button("Скрытые источники", x + 2 * (tabWidth + 6), 35, tabWidth, () -> show(2)).active = tab != 2;
        var search = addRenderableWidget(new EditBox(font, x, 57, w, 18, Component.literal("Поиск событий")));
        search.setHint(Component.literal("Найти тип, категорию или источник"));
        search.setValue(needle);
        search.setResponder(value -> {
            needle = value;
            scroll = 0;
            refreshRows();
        });

        int visible = Math.max(1, (height - 141) / 20);
        for (int i = 0; i < visible; i++) {
            int index = i;
            rows.add(button("", x, 80 + i * 20, w, () -> toggle(matches.get(scroll + index))));
        }

        int third = (w - 12) / 3;
        button("Показать всё", x, height - 28, third, () -> {
            selection = GuiEventVisibility.ALL_VISIBLE;
            refreshRows();
        });
        button("Отмена", x + third + 6, height - 28, third, this::onClose);
        button("Применить", x + 2 * (third + 6), height - 28, w - 2 * (third + 6), () -> {
            minecraft.setScreen(viewer);
            viewer.applyVisibility(selection);
        });
        refreshRows();
    }

    private void show(int selectedTab) {
        tab = selectedTab;
        scroll = 0;
        needle = "";
        clearWidgets();
        init();
    }

    private void toggle(Choice choice) {
        selection = switch (tab) {
            case 2 -> selection.sources().contains(choice.key())
                    ? selection.toggleSource(choice.key()) : selection.toggleMod(choice.key());
            case 1 -> selection.toggle(choice.action());
            default -> selection.toggle(choice.preset());
        };
        refreshRows();
    }

    private void refreshRows() {
        String query = needle.strip().toLowerCase(Locale.ROOT);
        var choices = switch (tab) {
            case 2 -> java.util.stream.Stream.concat(
                    selection.mods().stream().map(mod -> new Choice(mod, mod, 0, null)),
                    selection.sources().stream().map(source ->
                            new Choice(GuiEventVisibility.systemOriginLabel(source), source, 0, null)))
                    .sorted(java.util.Comparator.comparing(Choice::key)).toList();
            case 1 -> ACTIONS;
            default -> categories;
        };
        matches = choices.stream()
                .filter(choice -> choice.label().toLowerCase(Locale.ROOT).contains(query)
                        || choice.key().toLowerCase(Locale.ROOT).contains(query))
                .toList();
        scroll = Math.max(0, Math.min(scroll, matches.size() - rows.size()));
        for (int i = 0; i < rows.size(); i++) {
            var row = rows.get(i);
            row.visible = scroll + i < matches.size();
            if (!row.visible) continue;
            var choice = matches.get(scroll + i);
            boolean hidden = choice.action() == null ? selection.hides(choice.preset())
                    : selection.actions().contains(choice.action());
            String prefix = tab == 2 ? "Показать · " : hidden ? "Скрыто · " : "Показывать · ";
            row.setMessage(Component.literal(prefix + choice.label()));
            row.setTooltip(Tooltip.create(Component.literal(choice.key().isEmpty() ? choice.label()
                    : choice.label() + " · " + choice.key())));
        }
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        scroll = Math.max(0, scroll - (int) Math.signum(dy) * 3);
        refreshRows();
        return true;
    }

    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        LoggerGuiWidgets.beginFrame(g, width, height);
        g.drawCenteredString(font, title, width / 2, 9, 0xFFD700);
        g.drawCenteredString(font, "Личный фильтр журнала · запись событий продолжается", width / 2, 21, 0xAAAAAA);
        if (matches.isEmpty()) g.drawCenteredString(font, "Ничего не найдено", width / 2, 86, 0xAAAAAA);
        if (matches.size() > rows.size()) {
            g.drawCenteredString(font, "Прокрутите список", width / 2, height - 46, 0xAAAAAA);
        }
        for (var widget : renderables) widget.render(g, x, y, dt);
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
