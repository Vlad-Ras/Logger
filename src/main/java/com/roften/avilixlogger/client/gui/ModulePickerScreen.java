package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.ModLogFilters;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Picker for journal sections and dedicated mod journals. */
final class ModulePickerScreen extends LoggerScreen {
    private final Screen parent;
    private final LogViewerScreen journal;
    private final List<Map.Entry<Component, Consumer<Screen>>> entries;
    private int scroll;

    ModulePickerScreen(LogViewerScreen journal, List<Map.Entry<Component, Consumer<Screen>>> sections) {
        this(journal, journal, sections);
    }

    ModulePickerScreen(Screen parent, LogViewerScreen journal, List<Map.Entry<Component, Consumer<Screen>>> sections) {
        super(Component.literal(sections.isEmpty() ? "Журналы модов" : "Разделы"));
        this.parent = parent;
        this.journal = journal;
        this.entries = sections.isEmpty()
                ? ModLogFilters.MODULES.stream().map(module -> Map.entry(
                        (Component) Component.literal(module.title()),
                        (Consumer<Screen>) picker -> LogViewerClientHooks.openModule(picker, journal, module.preset()))).toList()
                : List.copyOf(sections);
    }

    private double scale(){return LogViewerScreen.sharedControlScale(width,height);}
    protected void init(){
        int w=Math.min(420,width-20),x=(width-w)/2,visible=Math.max(1,(height-80)/24);
        scroll = Math.min(scroll, Math.max(0, entries.size() - visible));
        for (int i = 0; i < visible && scroll + i < entries.size(); i++) {
            var entry = entries.get(scroll + i);
            addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x, 34 + i * 24, w, 20,
                    entry.getKey(), b -> entry.getValue().accept(this), this::scale));
        }
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,height-30,w,20,Component.literal("Назад"),b->onClose(),this::scale));
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){
        scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);clearWidgets();init();return true;
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        LoggerGuiWidgets.beginFrame(g,width,height);g.drawCenteredString(font,title,width/2,12,0xFFD700);
        if (entries.size() > Math.max(1, (height - 80) / 24)) {
            g.drawCenteredString(font, "Прокрутите список · " + (scroll + 1) + " / " + entries.size(),
                    width / 2, height - 45, 0xAAAAAA);
        }
        for(var widget:renderables)widget.render(g,mx,my,dt);
    }
    public void renderBackground(GuiGraphics g,int x,int y,float dt){}
    public void onClose(){
        minecraft.setScreen(parent);
        if (parent == journal) LogViewerClientHooks.resume(journal);
    }
    public boolean isPauseScreen(){return false;}
}
