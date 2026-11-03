package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.GuiTimeFilter;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import java.time.*;
import java.time.format.DateTimeFormatter;

/** Date and time are edited separately; the existing wire still carries absolute bounds. */
final class TimeRangeScreen extends Screen {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private final LogViewerScreen parent;
    private final ZoneId zone;
    private final java.util.function.Consumer<String> apply;
    private final String[] values = {"", "", "", ""};
    private final EditBox[] boxes = new EditBox[4];
    private String error = "";
    private GuiTimeFilter.Mode mode=GuiTimeFilter.Mode.SINCE;
    private int left, top, panelWidth;

    TimeRangeScreen(LogViewerScreen parent, ZoneId zone, String current, java.util.function.Consumer<String> apply) {
        super(Component.literal("Период журнала"));
        this.parent = parent; this.zone = zone; this.apply = apply;
        try {
            var selection = GuiTimeFilter.parse(current, ZonedDateTime.now(zone));
            if (selection.since() > 0) set(0, Instant.ofEpochMilli(selection.since()).atZone(zone));
            if (selection.until() >= 0) {
                set(2, Instant.ofEpochMilli(selection.until()).atZone(zone));
                mode=selection.since()==0?GuiTimeFilter.Mode.UNTIL:selection.until()-selection.since()==59_999&&selection.since()%60_000==0?GuiTimeFilter.Mode.MINUTE:GuiTimeFilter.Mode.RANGE;
            }
        } catch (IllegalArgumentException ignored) { error = "Введите начало и конец периода."; }
        if (values[0].isEmpty()) values[0] = LocalDate.now(zone).format(DATE);
    }
    private void set(int index, ZonedDateTime time) {
        values[index] = time.format(DATE); values[index + 1] = time.toLocalTime().withNano(0).toString();
    }
    private double scale() { return LogViewerScreen.sharedControlScale(width, height); }
    private void button(String label, int x, int y, int w, Runnable action) {
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,y,w,20,Component.literal(label),b->action.run(),this::scale));
    }
    protected void init() {
        panelWidth = Math.min(420, width - 20); left = (width-panelWidth)/2; top = Math.max(26,(height-200)/2);
        int dateWidth = (panelWidth-44)*2/3, timeWidth = panelWidth-44-dateWidth;
        String[] modes={"Начиная с","До","Минута","Период"};int modeWidth=(panelWidth-9)/4;
        for(int i=0;i<modes.length;i++) {
            var selected=GuiTimeFilter.Mode.values()[i];
            button((mode==selected?"• ":"")+modes[i],left+i*(modeWidth+3),top,modeWidth,()->{
                mode=selected;
                if(mode==GuiTimeFilter.Mode.UNTIL&&values[2].isBlank()){values[2]=values[0];values[3]=values[1];}
                clearWidgets();init();
            });
        }
        for (int i=0;i<4;i++) {
            final int index=i; int y=top+40+(i/2)*36;
            var box=new LoggerGuiWidgets.ScaledEditBox(font,left+34+(i%2==0?0:dateWidth+6),y,i%2==0?dateWidth:timeWidth,20,
                    Component.literal(i%2==0?"Дата":"Время"),this::scale);
            box.setMaxLength(i%2==0?10:8);box.setHint(Component.literal(i%2==0?"ДД.ММ.ГГГГ":"ЧЧ:ММ"));
            box.active=mode==GuiTimeFilter.Mode.RANGE || (mode==GuiTimeFilter.Mode.UNTIL?i>=2:i<2);
            box.setValue(values[i]); box.setResponder(value->values[index]=value); boxes[i]=box;addRenderableWidget(box);
        }
        String[] labels={"30 мин","2 часа","Сегодня","Вчера"}; int w=(panelWidth-9)/4;
        for(int i=0;i<labels.length;i++) {
            final int preset=i;button(labels[i],left+i*(w+3),top+116,w,()->quick(preset));
        }
        button("С начала журнала",left,top+140,panelWidth,()->finish("0m"));
        button("Отмена",left,top+174,(panelWidth-6)/2,this::onClose);
        button("Применить",left+(panelWidth+6)/2,top+174,(panelWidth-6)/2,this::submit);
    }
    private void quick(int preset) {
        var today=LocalDate.now(zone);
        finish(switch(preset){case 0->"30m";case 1->"2h";case 2->today.format(DATE);default->today.minusDays(1).format(DATE)+".."+today.minusDays(1).format(DATE);});
    }
    private void submit() {
        try { finish(GuiTimeFilter.form(mode,values[0],values[1],values[2],values[3],ZonedDateTime.now(zone))); }
        catch(IllegalArgumentException failure){error=mode==GuiTimeFilter.Mode.MINUTE?"Для минуты укажите верные дату и ЧЧ:ММ.":"Проверьте дату, время и порядок границ.";}
    }
    private void finish(String input) { minecraft.setScreen(parent);apply.accept(input); }
    public boolean keyPressed(int key,int scan,int modifiers) {
        if(key==257||key==335){submit();return true;}return super.keyPressed(key,scan,modifiers);
    }
    public void render(GuiGraphics g,int mx,int my,float dt) {
        LoggerGuiWidgets.beginFrame(g,width,height);
        g.drawCenteredString(font,title,width/2,top-25,0xFFD700);
        g.drawString(font,"Дата",left+34,top+27,0xAAAAAA,false);
        g.drawString(font,"Время",left+34+(panelWidth-44)*2/3+6,top+27,0xAAAAAA,false);
        g.drawString(font,"С",left,top+46,0xFFFFFF,false);g.drawString(font,"По",left,top+82,0xFFFFFF,false);
        g.drawString(font,"Часовой пояс: "+zone.getId(),left,top-13,0xAAAAAA,false);
        String hint=error.isBlank()?switch(mode){case SINCE->"Позже указанного времени, новые сверху";case UNTIL->"Не позднее указанного времени";case MINUTE->"Только указанная минута (00–59 секунд)";case RANGE->"Обе границы включены в период";}:error;
        int y=top+162;for(var line:font.split(Component.literal(hint),panelWidth)){g.drawString(font,line,left,y,0xFF7777,false);y+=10;}
        for(var widget:renderables)widget.render(g,mx,my,dt);
    }
    public void renderBackground(GuiGraphics g,int x,int y,float dt) {}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
