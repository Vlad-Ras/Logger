package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.ClientReportViewPayloads;
import com.roften.avilixlogger.net.ClientReportViewPayloads.Action;
import com.roften.avilixlogger.net.ClientReportViewPayloads.View;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

final class ClientReportScreen extends Screen {
    private enum Tab {
        OVERVIEW("Сводка"), MODS("Моды"), PACKS("Ресурс-паки"), COMMANDS("Команды");
        private final String label;
        Tab(String label) { this.label = label; }
    }

    private static final int LINE_HEIGHT = 12;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss XXX");
    private static long sequence;
    private final Screen parent;
    private final WrappedLogText layout = new WrappedLogText();
    private final List<Button> tabs = new ArrayList<>();
    private Tab tab = Tab.OVERVIEW;
    private String nickname = "";
    private String status = "Введите ник игрока и выберите действие.";
    private String commandStatus = "Серверные команды из журнала за последние 24 часа.";
    private List<Component> lines = List.of();
    private View snapshot;
    private List<String> commands = List.of();
    private long reportRequest;
    private long commandsRequest;
    private boolean commandsLoaded;
    private Button freshButton, cachedButton, copyButton;
    private int left, paneWidth, scroll;

    ClientReportScreen(Screen parent) {
        super(Component.literal("Отчёты о клиентах"));
        this.parent = parent;
    }

    static boolean available() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null || !connection.hasChannel(ClientReportViewPayloads.Query.TYPE)
                || !connection.hasChannel(ClientReportViewPayloads.View.TYPE)) return false;
        var log = connection.getCommands().getRoot().getChild("log");
        return log != null && log.getChild("client-report") != null && log.getChild("client-report-cache") != null;
    }

    @Override protected void init() {
        paneWidth = Math.min(720, width - 24);
        left = (width - paneWidth) / 2;
        int gap = 5;
        int part = (paneWidth - 2 * gap) / 3;
        var input = addRenderableWidget(new LoggerGuiWidgets.ScaledEditBox(font, left, 32, part, 20,
                Component.literal("Ник игрока"), this::scale));
        input.setHint(Component.literal("Ник Minecraft"));
        input.setMaxLength(16);
        input.setValue(nickname);
        input.setResponder(this::changeName);
        setInitialFocus(input);

        freshButton = button("Обновить отчёт", left + part + gap, 32, part, () -> send(Action.REQUEST));
        freshButton.setTooltip(Tooltip.create(Component.literal("Запросить новый отчёт у игрока онлайн.")));
        cachedButton = button("Открыть сохранённый", left + 2 * (part + gap), 32, paneWidth - 2 * (part + gap),
                () -> send(Action.CACHED));
        cachedButton.setTooltip(Tooltip.create(Component.literal("Последний отчёт за 24 часа, в том числе для офлайн-игрока.")));

        tabs.clear();
        int tabWidth = (paneWidth - 3 * gap) / 4;
        for (Tab value : Tab.values()) {
            int x = left + value.ordinal() * (tabWidth + gap);
            var button = button(value.label, x, 59, value == Tab.COMMANDS ? left + paneWidth - x : tabWidth,
                    () -> openTab(value));
            tabs.add(button);
        }
        int footerWidth = Math.min(110, paneWidth / 3);
        button("Назад", left, height - 30, footerWidth, this::onClose);
        copyButton = button("Копировать", left + paneWidth - footerWidth, height - 30, footerWidth, () ->
                minecraft.keyboardHandler.setClipboard(lines.stream().map(Component::getString).collect(Collectors.joining("\n"))));
        rebuild();
    }

    private Button button(String label, int x, int y, int w, Runnable action) {
        return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x, y, w, 20, Component.literal(label),
                b -> action.run(), this::scale));
    }

    private double scale() { return LogViewerScreen.sharedControlScale(width, height); }

    private void changeName(String value) {
        if (nickname.equals(value)) return;
        nickname = value;
        reportRequest = commandsRequest = 0;
        snapshot = null;
        commands = List.of();
        commandsLoaded = false;
        status = "Откройте сохранённый отчёт или обновите его, если игрок онлайн.";
        commandStatus = "Нажмите «Команды», чтобы загрузить записи для этого ника.";
        rebuild();
    }

    private void openTab(Tab value) {
        tab = value;
        if (tab == Tab.COMMANDS && !commandsLoaded && commandsRequest == 0 && ClientReportViewPayloads.validName(nickname)) {
            send(Action.COMMANDS);
        }
        rebuild();
    }

    private void send(Action action) {
        if (!available() || !ClientReportViewPayloads.validName(nickname)) return;
        long id = ++sequence;
        if (action == Action.COMMANDS) {
            commandsRequest = id;
            commandStatus = "Загружаю серверный журнал…";
        } else {
            reportRequest = id;
            snapshot = null;
            status = "Отправляю запрос для " + nickname + "…";
            tab = Tab.OVERVIEW;
        }
        rebuild();
        PacketDistributor.sendToServer(new ClientReportViewPayloads.Query(id, nickname, action));
    }

    void accept(View view) {
        if (view.id() != 0 && view.id() == reportRequest) {
            if (view.report() != null) {
                snapshot = view;
                status = "";
            } else status = view.message();
        } else if (view.id() != 0 && view.id() == commandsRequest) {
            commandsRequest = 0;
            commandsLoaded = !view.commands().isEmpty();
            commands = view.commands();
            commandStatus = view.message();
        } else return;
        rebuild();
    }

    private void rebuild() {
        var content = new ArrayList<Component>();
        if (tab == Tab.COMMANDS) {
            content.add(heading("Серверные команды · последние 24 часа"));
            if (!commandStatus.isEmpty()) content.add(Component.literal(commandStatus));
            commands.forEach(line -> content.add(Component.literal(line)));
        } else if (snapshot == null) {
            content.add(Component.literal(status));
        } else {
            var report = snapshot.report();
            switch (tab) {
                case OVERVIEW -> {
                    content.add(heading(snapshot.player()));
                    content.add(Component.literal("Получен · " + TIME.format(
                            Instant.ofEpochMilli(snapshot.receivedAt()).atZone(ZoneId.systemDefault()))));
                    content.add(Component.literal("Моды · " + report.mods().size() + "    Ресурс-паки · " + report.packs().size()));
                    if (report.truncated()) content.add(Component.literal("Список сокращён по лимиту отчёта.").withStyle(ChatFormatting.YELLOW));
                    if (!snapshot.matches().isEmpty()) {
                        content.add(heading("Совпадения по ресурс-пакам · " + snapshot.matches().size()));
                        snapshot.matches().forEach(pack -> content.add(match(pack)));
                    }
                    content.add(Component.literal("Данные прислал клиент. Совпадение по имени требует проверки.").withStyle(ChatFormatting.GRAY));
                }
                case MODS -> {
                    content.add(heading("Загруженные моды · " + report.mods().size()));
                    report.mods().forEach(mod -> content.add(Component.literal(mod.id())
                            .append(Component.literal("  ·  " + mod.version()).withStyle(ChatFormatting.GRAY))));
                }
                case PACKS -> {
                    content.add(heading("Выбранные ресурс-паки · " + report.packs().size()));
                    var matches = new HashSet<>(snapshot.matches());
                    report.packs().forEach(pack -> content.add(matches.contains(pack) ? match(pack) : Component.literal(pack)));
                }
                case COMMANDS -> throw new IllegalStateException("Commands have their own view");
            }
        }
        lines = List.copyOf(content);
        scroll = 0;
        for (int i = 0; i < tabs.size(); i++) {
            tabs.get(i).active = i != tab.ordinal() || tab == Tab.COMMANDS && !commandsLoaded && commandsRequest == 0;
        }
        if (freshButton != null) {
            boolean valid = ClientReportViewPayloads.validName(nickname);
            freshButton.active = valid;
            cachedButton.active = valid;
            copyButton.active = snapshot != null && tab != Tab.COMMANDS || tab == Tab.COMMANDS && !commands.isEmpty();
        }
    }

    private static Component heading(String text) { return Component.literal(text).withStyle(ChatFormatting.GOLD); }
    private static Component match(String pack) { return Component.literal("⚠ " + pack).withStyle(ChatFormatting.YELLOW); }

    @Override public void render(GuiGraphics g, int x, int y, float dt) {
        LoggerGuiWidgets.beginFrame(g, width, height);
        g.drawCenteredString(font, title, width / 2, 13, 0xFFDB87);
        int top = 85;
        int bottom = height - 37;
        if (bottom > top) {
            g.fill(left, top, left + paneWidth, bottom, 0xE519202A);
            g.fill(left, top, left + paneWidth, top + 1, 0xFF425368);
            var wrapped = layout.get(font, lines, paneWidth - 20);
            int visible = Math.max(1, (bottom - top - 12) / LINE_HEIGHT);
            scroll = Math.min(scroll, Math.max(0, wrapped.size() - visible));
            g.enableScissor(left + 8, top + 5, left + paneWidth - 7, bottom - 4);
            for (int i = scroll; i < Math.min(scroll + visible, wrapped.size()); i++) {
                g.drawString(font, wrapped.get(i), left + 9, top + 7 + (i - scroll) * LINE_HEIGHT, 0xFFE7EDF5);
            }
            g.disableScissor();
            if (wrapped.size() > visible) {
                int track = bottom - top - 12;
                int thumb = Math.max(10, track * visible / wrapped.size());
                int start = top + 6 + (track - thumb) * scroll / (wrapped.size() - visible);
                g.fill(left + paneWidth - 5, start, left + paneWidth - 2, start + thumb, 0xFF8295AE);
            }
        }
        for (var widget : renderables) widget.render(g, x, y, dt);
    }

    @Override public boolean mouseScrolled(double x, double y, double dx, double dy) {
        if (x < left || x >= left + paneWidth || y < 85 || y >= height - 37) return super.mouseScrolled(x, y, dx, dy);
        scroll = Math.max(0, scroll - 3 * (int) Math.signum(dy));
        return true;
    }

    @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
