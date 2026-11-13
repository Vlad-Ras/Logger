package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.CaptureTypePolicy;
import com.roften.avilixlogger.core.CaptureHandlers;
import net.minecraft.client.gui.components.EditBox;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;
import com.roften.avilixlogger.net.C2SCaptureTypesPayload;
import com.roften.avilixlogger.net.S2CCaptureTypesPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.ArrayList;
import java.util.List;

/** Separate admin page: same type presets as search, but controls future capture. */
public final class CaptureTypesScreen extends LoggerScreen {
    private static final String[] KEYS = {
            "all","block","container","entity","item","train","cannon",
            "chat","plane","cart","backpack","bearing","contraption"
    };
    private final Screen parent;
    private final List<Button> switches = new ArrayList<>();
    private long disabledMask;
    private Set<String> disabledHandlers = Set.of();
    private record HandlerGroup(String name, List<Integer> presets) {}
    private static final List<HandlerGroup> GROUPS = handlerGroups();
    private final List<Integer> filtered = new ArrayList<>();
    private final List<HandlerGroup> matchingGroups = new ArrayList<>();
    private HandlerGroup selectedGroup;
    private String searchText = "";
    private boolean handlers;
    private EditBox search;
    private Button tab;
    private int revision;
    private boolean loaded;
    private boolean canEdit;
    private boolean pending;
    private boolean lastSuccess = true;
    private String status = "Получение состояния сервера...";
    private int scroll;
    private Button enableAll, disableAll;

    private final LogViewerScreen viewer;

    public CaptureTypesScreen(Screen parent) {
        this(parent, parent instanceof LogViewerScreen journal ? journal : null);
    }

    CaptureTypesScreen(Screen parent, LogViewerScreen viewer) {
        super(Component.literal("AvilixLogger — Запись событий"));
        this.parent = parent;
        this.viewer = viewer;
    }

    CaptureTypesScreen(Screen parent, LogViewerScreen viewer, String mod) {
        this(parent, viewer);
        handlers = true;
        String origin = mod.isEmpty() ? "minecraft" : mod;
        selectedGroup = GROUPS.stream().filter(group -> group.presets().stream().anyMatch(preset ->
                matchesOrigin(CaptureHandlers.fromPreset(preset), origin))).findFirst().orElse(null);
        if (selectedGroup == null) searchText = mod;
    }

    private static boolean matchesOrigin(CaptureHandlers handler, String origin) {
        String key = handler.key();
        return switch (origin) {
            case "minecraft" -> key.startsWith("core.") || key.startsWith("mixin.");
            case "openpartiesandclaims" -> key.startsWith("opac.");
            case "scguns" -> key.startsWith("compat.scguns.") || key.startsWith("core.WeaponCombatAudit.");
            default -> key.startsWith(origin + ".") || key.startsWith("compat." + origin + ".");
        };
    }

    private static List<HandlerGroup> handlerGroups() {
        var groups = new LinkedHashMap<String, List<Integer>>();
        for (int i = 0; i < CaptureHandlers.count(); i++) {
            var handler = CaptureHandlers.at(i);
            String name = handler.title().split(" · ", 2)[0];
            if (name.equals("AE2")) name = "Applied Energistics 2";
            if (name.equals("OPAC")) name = "Open Parties and Claims";
            if (name.equals("ScorchedGuns")) name = "Scorched Guns";
            if (handler.key().startsWith("core.") || handler.key().startsWith("mixin.")) {
                if (!name.equals("Scorched Guns") && !name.equals("BlockPack")) name = "Minecraft / общие события";
            }
            groups.computeIfAbsent(name, ignored -> new ArrayList<>()).add(CaptureHandlers.PRESET_BASE + i);
        }
        return groups.entrySet().stream()
                .map(entry -> new HandlerGroup(entry.getKey(), List.copyOf(entry.getValue())))
                .sorted(java.util.Comparator.comparing(HandlerGroup::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private boolean showGroups() { return handlers && selectedGroup == null; }

    private int rowCount() { return showGroups() ? matchingGroups.size() : filtered.size(); }

    @Override protected void init() {
        switches.clear();
        int x = Math.max(8,(width - Math.min(width-20,450))/2);
        int w = Math.min(width-16,450);
        int tabWidth = viewer != null ? (w - 6) * 3 / 5 : w;
        tab = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,28,tabWidth,18,
                Component.empty(), b -> {
                    if (handlers && selectedGroup != null) selectedGroup = null;
                    else handlers = !handlers;
                    scroll = 0;
                    search.setValue("");
                    relayout();
                }, () -> 1));
        if (viewer != null) {
            addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x + tabWidth + 6, 28, w - tabWidth - 6, 18,
                    Component.literal("Видимость в GUI"), b -> minecraft.setScreen(new GuiVisibilityScreen(this, viewer)), () -> 1));
        }
        search = addRenderableWidget(new EditBox(font,x,49,w,18,Component.literal("Поиск обработчика")));
        search.setHint(Component.literal("Поиск по моду или названию"));
        search.setValue(searchText);
        search.setResponder(value -> { searchText = value; scroll = 0; relayout(); });
        int headerY = 72;
        int half = Math.max(45,(w - 6)/2);
        enableAll = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,headerY,half,18,
                Component.literal("Включить все"),b->setAll(true),()->1));
        disableAll = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x+half+6,headerY,w-half-6,18,
                Component.literal("Выключить все"),b->setAll(false),()->1));
        for (int row = 0; row < visibleCount(); row++) {
            final int index = row;
            var button = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,98,w,18,
                    Component.empty(), b -> {
                        int item = scroll + index;
                        if (item >= rowCount()) return;
                        if (showGroups()) {
                            selectedGroup = matchingGroups.get(item);
                            scroll = 0;
                            relayout();
                        } else toggle(filtered.get(item));
                    }, () -> 1));
            switches.add(button);
        }
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,height-28,w,19,
                Component.literal("Назад"),b->onClose(),()->1));
        // A fresh server response prevents accidentally using a previous world's state.
        loaded=false;pending=false;
        relayout();
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null && connection.hasChannel(C2SCaptureTypesPayload.TYPE))
            PacketDistributor.sendToServer(new C2SCaptureTypesPayload(C2SCaptureTypesPayload.Action.QUERY,0,false,0));
    }

    private int visibleCount() { return Math.max(1, (height - 180) / 21); }

    private boolean enabled(int id) {
        var handler = CaptureHandlers.fromPreset(id);
        return handler == null ? (disabledMask & (1L << (id - 1))) == 0 : !disabledHandlers.contains(handler.key());
    }

    private String label(int id) {
        var handler = CaptureHandlers.fromPreset(id);
        if (handler != null) return handler.title();
        return categoryLabel(id);
    }

    static String categoryLabel(int id) {
        String title = CaptureTypePolicy.title(id);
        return title != null ? title : Component.translatable("gui.avilixlogger.type." + KEYS[id]).getString();
    }

    private boolean matches(int preset, String query) {
        return label(preset).toLowerCase(Locale.ROOT).contains(query)
                || CaptureHandlers.fromPreset(preset).key().toLowerCase(Locale.ROOT).contains(query);
    }

    private void relayout() {
        tab.setMessage(Component.literal(!handlers ? "Категории · открыть обработчики по модам"
                : showGroups() ? "Обработчики по модам · открыть категории" : "← Моды · " + selectedGroup.name()));
        search.setHint(Component.literal(showGroups() ? "Найти мод или обработчик" : "Поиск по названию"));
        filtered.clear();
        matchingGroups.clear();
        String query = search.getValue().strip().toLowerCase(Locale.ROOT);
        if (showGroups()) {
            for (var group : GROUPS) {
                if (group.name().toLowerCase(Locale.ROOT).contains(query)
                        || group.presets().stream().anyMatch(preset -> matches(preset, query))) matchingGroups.add(group);
            }
        } else if (handlers) {
            boolean wholeGroup = selectedGroup.name().toLowerCase(Locale.ROOT).contains(query);
            for (int preset : selectedGroup.presets()) if (wholeGroup || matches(preset, query)) filtered.add(preset);
        } else {
            for (int id = 1; id <= CaptureTypePolicy.GROUPS; id++) {
                if (label(id).toLowerCase(Locale.ROOT).contains(query)) filtered.add(id);
            }
        }
        scroll = Math.max(0, Math.min(scroll, rowCount() - visibleCount()));
        for (int i = 0; i < switches.size(); i++) {
            var button = switches.get(i);
            int index = scroll + i;
            button.visible = index < rowCount();
            if (!button.visible) continue;
            button.setY(98 + i * 21);
            String title;
            if (showGroups()) {
                var group = matchingGroups.get(index);
                long enabledCount = group.presets().stream().filter(this::enabled).count();
                title = group.name() + "  ·  " + (loaded ? enabledCount + " / " : "") + group.presets().size();
                button.setMessage(Component.literal(title));
                button.active = true;
            } else {
                int id = filtered.get(index);
                boolean active = loaded && enabled(id);
                title = label(id);
                button.setMessage(Component.literal((active ? "●  " : "○  ") + title
                        + (active ? "  —  ВКЛ" : "  —  ВЫКЛ")));
                button.active = loaded && canEdit && !pending;
            }
            button.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal(title)));
        }
        boolean bulk = !handlers || showGroups();
        enableAll.setMessage(Component.literal(handlers ? "Включить все обработчики" : "Включить все категории"));
        disableAll.setMessage(Component.literal(handlers ? "Выключить все обработчики" : "Выключить все категории"));
        enableAll.visible = disableAll.visible = bulk;
        enableAll.active = disableAll.active = bulk && loaded && canEdit && !pending;
    }

    private void toggle(int preset) {
        if(!loaded||!canEdit||pending)return;
        pending=true;relayout();
        boolean enabled = !enabled(preset);
        PacketDistributor.sendToServer(new C2SCaptureTypesPayload(
                C2SCaptureTypesPayload.Action.SET,preset,enabled,revision));
    }
    private void setAll(boolean enable) {
        if(!loaded||!canEdit||pending)return;
        pending=true;relayout();
        PacketDistributor.sendToServer(new C2SCaptureTypesPayload(
                handlers
                        ? (enable ? C2SCaptureTypesPayload.Action.ENABLE_HANDLERS : C2SCaptureTypesPayload.Action.DISABLE_HANDLERS)
                        : (enable ? C2SCaptureTypesPayload.Action.ENABLE_ALL : C2SCaptureTypesPayload.Action.DISABLE_ALL),
                0,enable,revision));
    }
    public void apply(S2CCaptureTypesPayload state) {
        if(state==null)return;
        disabledMask=state.disabledMask()&CaptureTypePolicy.ALL_DISABLED;
        disabledHandlers = Set.copyOf(state.disabledHandlers());
        revision=state.revision();
        canEdit=state.canEdit();lastSuccess=state.success();
        status=state.message().isBlank()
                ? (canEdit?"Изменения применяются немедленно и сохраняются в мире"
                          :"Просмотр доступен. Для изменения нужны права администратора")
                : state.message();
        pending=false;loaded=true;
        relayout();
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy) {
        scroll=Math.max(0,Math.min(rowCount()-visibleCount(),scroll-(int)Math.signum(dy)*2));
        relayout();return true;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        LoggerGuiWidgets.beginFrame(g,width,height);
        int x=Math.max(8,(width-Math.min(width-20,450))/2);

        g.drawCenteredString(font,title,width/2,8,0xFFFFAA00);
        g.drawCenteredString(font,Component.literal("Управление сбором, не фильтр существующих записей"),
                width/2,18,0xFFAAAAAA);
        int count=visibleCount();
        if(scroll+count<rowCount())
            g.drawString(font,"▼ Остальные позиции ниже",x,Math.min(height-70,101+count*21),0xFF999999);
        g.drawCenteredString(font,font.plainSubstrByWidth(status,Math.max(30,width-24)),
                width/2,height-62,lastSuccess?0xFFAAAAAA:0xFFFF6666);
        String overflow=!loaded?"Получение режима переполнения…":(disabledMask&(1L<<29))==0
                ?"Переполнение: пауза и автоматическое возобновление"
                :"Без паузы: полная очередь задерживает серверный тик";
        g.drawCenteredString(font,overflow,
                width/2,height-48,0xFFFFAA55);
        for(var widget:renderables)widget.render(g,mx,my,partial);
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
