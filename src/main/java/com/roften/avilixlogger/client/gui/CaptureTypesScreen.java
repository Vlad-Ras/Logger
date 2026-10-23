package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.CaptureTypePolicy;
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
public final class CaptureTypesScreen extends Screen {
    private static final String[] KEYS = {
            "all","block","container","entity","item","train","cannon",
            "chat","plane","cart","backpack","bearing","contraption"
    };
    private final Screen parent;
    private final List<Button> switches = new ArrayList<>();
    private int disabledMask;
    private int revision;
    private boolean loaded;
    private boolean canEdit;
    private boolean pending;
    private boolean lastSuccess = true;
    private String status = "Получение состояния сервера...";
    private int scroll;
    private Button enableAll, disableAll;

    public CaptureTypesScreen(Screen parent) {
        super(Component.literal("AvilixLogger 2.1.24 — Запись событий"));
        this.parent = parent;
    }

    @Override protected void init() {
        switches.clear();
        int x = Math.max(8,(width - Math.min(width-20,450))/2);
        int w = Math.min(width-16,450);
        int headerY = 28;
        int half = Math.max(45,(w - 6)/2);
        enableAll = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,headerY,half,18,
                Component.literal("Включить все"),b->setAll(true),()->1));
        disableAll = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x+half+6,headerY,w-half-6,18,
                Component.literal("Выключить все"),b->setAll(false),()->1));
        for (int preset=1;preset<=CaptureTypePolicy.GROUPS;preset++) {
            final int id = preset;
            var button = addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,54,w,18,
                    Component.empty(),b->toggle(id),()->1));
            switches.add(button);
        }
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,height-28,w,19,
                Component.literal("Назад"),b->onClose(),()->1));
        relayout();
        // A fresh server response prevents accidentally using a previous world's state.
        loaded=false;pending=false;
        if (Minecraft.getInstance().getConnection() != null)
            PacketDistributor.sendToServer(new C2SCaptureTypesPayload(C2SCaptureTypesPayload.Action.QUERY,0,false,0));
    }

    private int visibleCount() { return Math.max(1,Math.min(CaptureTypePolicy.GROUPS,(height - 134)/21)); }
    private void relayout() {
        int visible = visibleCount();
        scroll=Math.max(0,Math.min(scroll,CaptureTypePolicy.GROUPS-visible));
        for(int i=0;i<switches.size();i++){
            var button=switches.get(i);
            boolean shown=i>=scroll&&i<scroll+visible;
            button.visible=shown;
            button.setY(54+(i-scroll)*21);
            boolean enabled=loaded&&(disabledMask&(1<<i))==0;
            button.setMessage(Component.literal((enabled?"●  ":"○  ")+
                    Component.translatable("gui.avilixlogger.type."+KEYS[i+1]).getString()
                    +(enabled?"  —  ВКЛ":"  —  ВЫКЛ")));
            button.active=loaded&&canEdit&&!pending;
        }
        enableAll.active=loaded&&canEdit&&!pending;
        disableAll.active=loaded&&canEdit&&!pending;
    }

    private void toggle(int preset) {
        if(!loaded||!canEdit||pending)return;
        pending=true;relayout();
        boolean enabled=(disabledMask & (1<<(preset-1)))!=0;
        PacketDistributor.sendToServer(new C2SCaptureTypesPayload(
                C2SCaptureTypesPayload.Action.SET,preset,enabled,revision));
    }
    private void setAll(boolean enable) {
        if(!loaded||!canEdit||pending)return;
        pending=true;relayout();
        PacketDistributor.sendToServer(new C2SCaptureTypesPayload(
                enable?C2SCaptureTypesPayload.Action.ENABLE_ALL:C2SCaptureTypesPayload.Action.DISABLE_ALL,
                0,enable,revision));
    }
    public void apply(S2CCaptureTypesPayload state) {
        if(state==null)return;
        disabledMask=state.disabledMask()&CaptureTypePolicy.ALL_DISABLED;
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
        scroll=Math.max(0,Math.min(CaptureTypePolicy.GROUPS-visibleCount(),scroll-(int)Math.signum(dy)*2));
        relayout();return true;
    }
    @Override public void render(GuiGraphics g,int mx,int my,float partial) {
        LoggerGuiWidgets.beginFrame(g,width,height);
        int x=Math.max(8,(width-Math.min(width-20,450))/2);
        g.fill(x-5,4,x+Math.min(width-16,450)+5,height-4,0xCC090B0F);
        g.drawCenteredString(font,title,width/2,8,0xFFFFAA00);
        g.drawCenteredString(font,Component.literal("Управление сбором, не фильтр существующих записей"),
                width/2,18,0xFFAAAAAA);
        int count=visibleCount();
        if(scroll>0)g.drawString(font,"▲ Прокрутите список",x,49,0xFF999999);
        if(scroll+count<CaptureTypePolicy.GROUPS)
            g.drawString(font,"▼ Остальные типы ниже",x,Math.min(height-70,57+count*21),0xFF999999);
        g.drawCenteredString(font,font.plainSubstrByWidth(status,Math.max(30,width-24)),
                width/2,height-62,lastSuccess?0xFFAAAAAA:0xFFFF6666);
        g.drawCenteredString(font,"Выключенные события не сохраняются и не могут быть откатаны",
                width/2,height-48,0xFFFFAA55);
        for(var widget:renderables)widget.render(g,mx,my,partial);
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float partial) {}
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }
}
