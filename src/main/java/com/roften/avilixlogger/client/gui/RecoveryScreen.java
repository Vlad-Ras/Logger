package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.RecoveryTimeInput;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Recovery-specific form built from the same controls and time parser as the main journal. */
public final class RecoveryScreen extends LoggerScreen {
    private final Screen parent;
    private final ZoneId zone;
    private final String[] values={"","","","","20",""};
    private final EditBox[] inputs=new EditBox[6];
    private final DateTimeFormatter date=DateTimeFormatter.ofPattern("dd.MM.uuuu"),clock=DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private boolean replay=true;
    private RecoveryTimeInput.Area area=RecoveryTimeInput.Area.RADIUS;
    private String error="";
    private int left,top,panelWidth,rowY,presetY,areaY,filtersY,controlHeight;
    private double textScale=1;
    public RecoveryScreen(Screen parent) {
        super(Component.literal("Восстановление построек"));this.parent=parent;this.zone=LogViewerScreen.logTimeZone();quick(30);
    }
    private void set(int index,ZonedDateTime value) {values[index]=date.format(value);values[index+1]=clock.format(value);}
    private void quick(int minutes) {var now=ZonedDateTime.now(zone);set(0,now.minusMinutes(minutes));set(2,now);error="";}
    private void remember() {for(int i=0;i<inputs.length;i++)if(inputs[i]!=null)values[i]=inputs[i].getValue();}
    private double scale() {return LogViewerScreen.sharedControlScale(width,height);}
    private Button button(String label,int x,int y,int w,Runnable action) {
        return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,y,w,controlHeight,Component.literal(label),b->action.run(),this::scale));
    }
    private void refresh() {clearWidgets();init();}
    protected void init() {
        boolean compact=height<300;
        controlHeight=compact?16:20;
        panelWidth=Math.min(480,width-20);left=(width-panelWidth)/2;top=compact?30:35;
        rowY=top+(compact?30:39);presetY=rowY+(compact?48:60);areaY=presetY+(compact?22:26);filtersY=areaY+(compact?28:34);
        int half=(panelWidth-6)/2;
        button((!replay?"• ":"")+"Состояние на момент",left,top,half,()->{remember();replay=false;error="";refresh();});
        button((replay?"• ":"")+"Изменения за период",left+half+6,top,half,()->{remember();replay=true;error="";refresh();});
        int dateWidth=(panelWidth-40)/2,timeWidth=panelWidth-40-dateWidth;
        for(int i=0;i<4;i++) {
            int index=i;
            var box=new LoggerGuiWidgets.ScaledEditBox(font,left+28+(i%2==0?0:dateWidth+6),rowY+(i/2)*(compact?26:32),
                    i%2==0?dateWidth:timeWidth,controlHeight,Component.literal(i%2==0?"Дата":"Время"),this::scale);
            box.setMaxLength(i%2==0?10:12);box.setHint(Component.literal(i%2==0?"ДД.ММ.ГГГГ":"ЧЧ:ММ"));
            box.setValue(values[i]);box.setResponder(value->values[index]=value);box.active=replay||i<2;
            inputs[i]=box;addRenderableWidget(box);
        }
        int w=(panelWidth-9)/4;
        int[] periods={5,30,60,1440};String[] names={"5 минут","30 минут","Час","Сутки"};
        for(int i=0;i<4;i++){int period=periods[i];button(names[i],left+i*(w+3),presetY,w,()->{remember();quick(period);refresh();});}
        int third=(panelWidth-6)/3;
        for(int i=0;i<3;i++) {
            var selected=RecoveryTimeInput.Area.values()[i];String label=new String[]{"Радиус","Выделение WE","Блок под прицелом"}[i];
            button((area==selected?"• ":"")+label,left+i*(third+3),areaY,third,()->{remember();area=selected;refresh();});
        }
        for(int i=4;i<6;i++) {
            int index=i;
            var box=new LoggerGuiWidgets.ScaledEditBox(font,left+(i-4)*(half+6),filtersY,half,controlHeight,Component.literal(i==4?"Радиус":"Игроки"),this::scale);
            box.setMaxLength(i==4?3:271);box.setValue(values[i]);box.setResponder(value->values[index]=value);
            box.setHint(Component.literal(i==4?"1–256":"Все источники"));box.active=i==5||area==RecoveryTimeInput.Area.RADIUS;
            inputs[i]=box;addRenderableWidget(box);
        }
        button("Назад",left,height-28,half,this::onClose);
        button("Предпросмотр",left+half+6,height-28,half,this::preview);
        textScale=LogViewerScreen.sharedControlScale(width,height);
    }
    private void preview() {
        remember();
        try {
            var window=RecoveryTimeInput.form(replay,values[0],values[1],values[2],values[3],ZonedDateTime.now(zone));
            String flags=window.flags(area,values[4],values[5]);
            LoggerActionsScreen.run(this,replay?"replay":"rollback",List.of(flags));
        } catch(IllegalArgumentException failure) {error=failure.getMessage();}
    }
    public static boolean available() {return LoggerActionsScreen.canRun("replay")&&LoggerActionsScreen.canRun("rollback");}
    public void render(GuiGraphics g,int mx,int my,float dt) {
        LoggerGuiWidgets.beginFrame(g,width,height);
        g.drawCenteredString(font,title,width/2,10,0xFFAA00);
        label(g,"Дата",left+28,rowY-10);label(g,"Время · "+zone.getId(),left+28+(panelWidth-40)/2+6,rowY-10);
        label(g,replay?"С":"На",left,rowY+6);label(g,"По",left,rowY+(height<300?26:32)+6);
        label(g,"Радиус в блоках",left,filtersY-10);label(g,"Игроки через запятую (пусто — все)",left+(panelWidth+6)/2,filtersY-10);
        int helpY=filtersY+controlHeight+6;
        String hint=error.isEmpty()?(replay?"Повторить события за указанный период после краша. Обе границы включены. "
                :"Вернуть состояние на указанный момент: отменить события строго после него. ")
                +"Сначала будет показан план и число объектов. Применение требует отдельного подтверждения.":error;
        g.enableScissor(left,helpY,left+panelWidth,height-34);
        g.pose().pushPose();g.pose().translate(left,helpY,0);g.pose().scale((float)textScale,(float)textScale,1);
        int y=0;for(var line:font.split(Component.literal(hint),(int)(panelWidth/textScale))) {g.drawString(font,line,0,y,error.isEmpty()?0xAAAAAA:0xFF7777);y+=11;}
        g.pose().popPose();
        g.disableScissor();
        for(var widget:renderables)widget.render(g,mx,my,dt);
    }
    private void label(GuiGraphics g,String text,int x,int y) {
        // Same scale used by the journal controls keeps labels inside narrow forms.
        g.pose().pushPose();g.pose().translate(x,y,0);g.pose().scale((float)textScale,(float)textScale,1);
        g.drawString(font,text,0,0,0xAAAAAA);g.pose().popPose();
    }
    public void resize(Minecraft minecraft,int width,int height) {remember();super.resize(minecraft,width,height);}
    public void onClose() {remember();minecraft.setScreen(parent);}
    public void renderBackground(GuiGraphics g,int x,int y,float dt) {}
    public boolean isPauseScreen() {return false;}
}
