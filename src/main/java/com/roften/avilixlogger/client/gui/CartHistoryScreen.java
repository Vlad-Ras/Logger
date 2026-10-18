package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** Dedicated minecart page. Coordinates, entry and snapshot actions are always explicit. */
public final class CartHistoryScreen extends Screen {
    private static long sequence;
    private final Screen parent;
    private String id;
    private EditBox idBox;
    private final List<S2CCartHistoryPayload.Row> rows=new ArrayList<>();
    private final Deque<Long> previous=new ArrayDeque<>();
    private long cursor,next,request;
    private int selected=-1,listScroll,detailScroll;
    private boolean loading,canRestore;
    private String status="",extraDetails="";
    private long jsonRequest=-1;
    private Button prev,nextButton,restore,rollback,latest,copyId,copyEntry,json;
    private int listX,listY,listW,listH,detailX,detailY,detailW,detailH;
    public CartHistoryScreen(Screen parent,String id){super(Component.literal("Вагонеточные штуковины"));this.parent=parent;this.id=id==null?"":id;}
    protected void init(){
        int margin=10,top=32;int inputW=Math.max(80,width-100);
        idBox=new EditBox(font,margin,top,inputW,20,Component.literal("ID штуковины"));idBox.setMaxLength(36);idBox.setValue(id);idBox.setHint(Component.literal("UUID штуковины; пусто — все"));addRenderableWidget(idBox);
        button("Найти",margin+inputW+5,top,75,()->{if(loading)return;id=idBox.getValue().trim();cursor=0;previous.clear();load();});
        button("×",width-30,8,20,this::onClose);
        int bodyTop=62,bodyBottom=height-86;
        if(width>=760){listX=margin;listY=bodyTop;listW=(width-30)*3/5;listH=bodyBottom-bodyTop;detailX=listX+listW+10;detailY=bodyTop;detailW=width-detailX-margin;detailH=listH;}
        else{listX=margin;listY=bodyTop;listW=width-margin*2;listH=Math.max(40,(bodyBottom-bodyTop)*3/5);detailX=margin;detailY=listY+listH+6;detailW=listW;detailH=bodyBottom-detailY;}
        int bottom=height-76;prev=button("←",margin,bottom,30,()->{if(!previous.isEmpty()){cursor=previous.pop();load();}});
        nextButton=button("→",margin+35,bottom,30,()->{if(next>0){previous.push(cursor);cursor=next;load();}});
        int copyW=Math.max(45,(width-105)/3);
        copyId=button("ID",margin+75,bottom,copyW,()->copy(selectedId()));
        copyEntry=button("entry",margin+80+copyW,bottom,copyW,()->{var r=chosen();if(r!=null)copy(Long.toString(r.entry()));});
        json=button("JSON",margin+85+copyW*2,bottom,copyW,this::requestJson);
        copyId.setTooltip(Tooltip.create(Component.literal("Скопировать полный ID штуковины")));
        copyEntry.setTooltip(Tooltip.create(Component.literal("Скопировать номер выбранной записи (entry)")));
        int w=(width-30)/3;
        latest=button("Последнее состояние → предмет",margin,height-48,w,()->recover(0));
        restore=button("Состояние строки → предмет",margin+w+5,height-48,w,()->{var r=chosen();if(r!=null)recover(r.entry());});
        rollback=button("Откат действий к строке",margin+2*(w+5),height-48,width-margin-(margin+2*(w+5)),()->{var r=chosen();if(r!=null)command("log cart rollback "+r.cartId()+" "+r.entry());});
        restore.setTooltip(Tooltip.create(Component.literal("Выдать сохранённую штуковину в свободный слот; действия в мире не откатываются.")));
        rollback.setTooltip(Tooltip.create(Component.literal("Предпросмотр отката периода: может изменять мир и связанные инвентари. Для пропавшей штуковины используйте выдачу в предмет.")));
        updateButtons();
    }
    private Button button(String text,int x,int y,int w,Runnable action){int available=Math.max(20,w);String label=font.width(text)>available-10?font.plainSubstrByWidth(text,Math.max(1,available-18))+"…":text;
        var button=addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,available,20).build());
        button.setTooltip(Tooltip.create(Component.literal(text)));return button;}
    public void load(){
        if(loading)return;
        try{if(!id.isBlank())UUID.fromString(id);}catch(IllegalArgumentException e){status="Неверный UUID штуковины";return;}
        loading=true;request=++sequence;status="Загрузка истории…";updateButtons();
        PacketDistributor.sendToServer(new C2SCartHistoryPayload(request,id,cursor));
    }
    public void apply(S2CCartHistoryPayload p){
        if(p.kind()==S2CCartHistoryPayload.ERROR){loading=false;status=p.message();updateButtons();return;}
        if(p.kind()!=S2CCartHistoryPayload.PAGE||p.request()!=request||!p.cartId().equals(id))return;
        loading=false;rows.clear();rows.addAll(p.rows());next=p.next();canRestore=p.canRestore();selected=-1;listScroll=0;detailScroll=0;extraDetails="";jsonRequest=-1;status=p.message();updateButtons();
    }
    private void updateButtons(){if(prev==null)return;var r=chosen();prev.active=!loading&&!previous.isEmpty();nextButton.active=!loading&&next>0;
        copyId.active=!selectedId().isBlank();copyEntry.active=r!=null;json.active=r!=null&&!loading;
        latest.active=canRestore&&!loading&&!selectedId().isBlank();restore.active=canRestore&&!loading&&r!=null;rollback.active=canRestore&&!loading&&r!=null&&r.checkpoint();}
    private S2CCartHistoryPayload.Row chosen(){return selected>=0&&selected<rows.size()?rows.get(selected):null;}
    private String selectedId(){var r=chosen();return r==null?id:r.cartId();}
    private void recover(long entry){if(!selectedId().isBlank())command("log cart recover "+selectedId()+" "+(entry==0?"latest":entry));}
    private void requestJson(){var r=chosen();if(r==null)return;jsonRequest=r.entry();status="Загрузка JSON…";PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.entry(),C2SRequestDetailsPayload.Mode.JSON,new long[]{r.entry()},r.location().split(" · ",2)[0]));}
    public void acceptDetails(S2CLogDetailsPayload p){
        if(p.mode()==C2SRequestDetailsPayload.Mode.DETAILS){var row=chosen();if(row!=null&&row.entry()==p.entryId())extraDetails="\n\n"+p.lines().stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));return;}
        if(p.entryId()!=jsonRequest||p.mode()!=C2SRequestDetailsPayload.Mode.JSON)return;
        jsonRequest=-1;copy(p.lines().stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n")));
    }
    private void copy(String value){Minecraft.getInstance().keyboardHandler.setClipboard(value);status="Скопировано в буфер обмена";}
    private static void command(String value){var p=Minecraft.getInstance().player;if(p!=null)p.connection.sendCommand(value);}
    public boolean mouseClicked(double x,double y,int button){
        if(button==0&&x>=listX&&x<listX+listW&&y>=listY&&y<listY+listH){int row=listScroll+(int)(y-listY)/38;if(row<rows.size()){selected=row;idBox.setValue(rows.get(row).cartId());detailScroll=0;extraDetails="";updateButtons();var r=chosen();PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.entry(),C2SRequestDetailsPayload.Mode.DETAILS,new long[]{r.entry()},r.location().split(" · ",2)[0]));return true;}}
        return super.mouseClicked(x,y,button);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(x>=listX&&x<listX+listW&&y>=listY&&y<listY+listH){listScroll=Math.max(0,Math.min(Math.max(0,rows.size()-Math.max(1,listH/38)),listScroll-(int)Math.signum(dy)));return true;}
        if(x>=detailX&&x<detailX+detailW&&y>=detailY&&y<detailY+detailH){detailScroll=Math.max(0,detailScroll-(int)Math.signum(dy)*3);return true;}
        return super.mouseScrolled(x,y,dx,dy);
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,0xEE11151B);g.drawCenteredString(font,title,width/2,10,0xFFFFFF);
        g.fill(listX,listY,listX+listW,listY+listH,0xFF1B232D);g.enableScissor(listX,listY,listX+listW,listY+listH);
        if(rows.isEmpty())g.drawString(font,loading?"Загрузка…":"Записей нет. Введите ID или откройте общую историю.",listX+5,listY+8,0xAABBCD);
        for(int i=listScroll;i<rows.size();i++){int y=listY+(i-listScroll)*38;if(y>=listY+listH)break;var r=rows.get(i);
            if(i==selected)g.fill(listX,y,listX+listW,y+37,0xFF29496B);
            g.drawString(font,font.plainSubstrByWidth("entry="+r.entry()+"  "+r.time()+"  "+r.action(),listW-12),listX+6,y+4,0xFFFFFF);
            g.drawString(font,font.plainSubstrByWidth(r.actor()+" · "+r.location(),listW-12),listX+6,y+16,0xB5C5D5);
            g.drawString(font,font.plainSubstrByWidth(r.snapshot()?"Полный снимок состояния":"Состояние можно собрать из истории",listW-12),listX+6,y+27,r.snapshot()?0x8ED5A1:0x8294A6);
        }g.disableScissor();
        g.fill(detailX,detailY,detailX+detailW,detailY+detailH,0xFF19212A);
        var r=chosen();String text=r==null?"Выберите строку.\n\nСостояние → предмет: вернуть саму штуковину.\n\nОткат действий: изменить мир за период.\n\nID и entry копируются кнопками.":r.action()+"\n"+r.time()+"\n"+r.actor()+"\n"+r.location()+"\n\n"+r.detail()+extraDetails;
        var lines=font.split(Component.literal(text),Math.max(30,detailW-12));detailScroll=Math.min(detailScroll,Math.max(0,lines.size()-Math.max(1,detailH/11)));
        g.enableScissor(detailX,detailY,detailX+detailW,detailY+detailH);
        for(int i=detailScroll;i<lines.size();i++){int y=detailY+5+(i-detailScroll)*11;if(y>=detailY+detailH)break;g.drawString(font,lines.get(i),detailX+6,y,0xDCE6F0);}g.disableScissor();
        g.drawString(font,font.plainSubstrByWidth(status,width-20),10,height-19,0xC9D7E4);super.render(g,mx,my,dt);
    }
    public void onClose(){Minecraft.getInstance().setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    public static CartHistoryScreen owner(Screen current){return current instanceof CartHistoryScreen s?s:current instanceof Confirmation c?c.parent:null;}
    public void confirmation(String message,String command){Minecraft.getInstance().setScreen(new Confirmation(this,message,command));}
    private static final class Confirmation extends Screen {
        private final CartHistoryScreen parent;private final String message,command;private int scroll;
        Confirmation(CartHistoryScreen parent,String message,String command){super(Component.literal("Подтверждение операции"));this.parent=parent;this.message=message;this.command=command;}
        protected void init(){addRenderableWidget(Button.builder(Component.literal("Подтвердить"),b->{command(command);Minecraft.getInstance().setScreen(parent);}).bounds(width/2-155,height-35,150,20).build());
            addRenderableWidget(Button.builder(Component.literal("Отмена"),b->onClose()).bounds(width/2+5,height-35,150,20).build());}
        public void render(GuiGraphics g,int x,int y,float dt){g.fill(0,0,width,height,0xF511151B);g.drawCenteredString(font,title,width/2,12,0xFFFFFF);var lines=font.split(Component.literal(message),width-40);scroll=Math.min(scroll,Math.max(0,lines.size()-(height-90)/12));g.enableScissor(20,40,width-20,height-50);
            for(int i=scroll;i<lines.size();i++)g.drawString(font,lines.get(i),20,40+(i-scroll)*12,0xE4ECF4);g.disableScissor();super.render(g,x,y,dt);}
        public boolean mouseScrolled(double x,double y,double dx,double dy){scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);return true;}
        public void onClose(){command(command.startsWith("log cart confirm-recovery")?"log cart cancel-recovery":"log cart cancel");Minecraft.getInstance().setScreen(parent);}
        public boolean isPauseScreen(){return false;}
    }
}
