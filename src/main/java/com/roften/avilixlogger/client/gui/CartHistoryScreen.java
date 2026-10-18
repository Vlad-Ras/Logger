package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import static com.roften.avilixlogger.net.C2SCartActionPayload.Action;

/** Complete minecart workflow. GUI uses typed payloads, including confirmation and cancellation. */
public final class CartHistoryScreen extends Screen {
    private static long sequence;
    private final Screen parent;
    private String id,actor="";
    private boolean checkpointsOnly,loading,canRestore,operationRunning,session;
    private EditBox idBox,actorBox;
    private final List<S2CCartHistoryPayload.Row> rows=new ArrayList<>();
    private final Deque<Long> previous=new ArrayDeque<>();
    private final List<String> journal=new ArrayList<>();
    private long cursor,next,request,jsonRequest=-1,seekEntry;
    private int selected=-1,listScroll,detailScroll;
    private String status="",extraDetails="";
    private Button prev,nextButton,restore,latest,copyId,copyEntry,json,checkpointButton;
    private int listX,listY,listW,listH,detailX,detailY,detailW,detailH;
    public CartHistoryScreen(Screen parent,String id){super(Component.literal("Вагонеточные штуковины"));this.parent=parent;this.id=id==null?"":id;}
    protected void init(){
        int margin=10,inputW=width-100;
        idBox=box(margin,32,inputW,36,id,"UUID штуковины; пусто — все");
        button(this,"Найти ID",margin+inputW+5,32,75,()->search(idBox.getValue().trim(),actorBox.getValue().trim(),checkpointsOnly,0));
        button(this,"×",width-30,7,20,this::onClose);
        int actorW=width-170;
        actorBox=box(margin,56,actorW,64,actor,"Ник игрока");
        button(this,"По игроку",margin+actorW+5,56,70,()->{
            if(actorBox.getValue().isBlank()){message("Введите ник игрока",true);return;}search("",actorBox.getValue().trim(),true,0);
        });
        button(this,"В руке",width-80,56,70,()->action(Action.HELD,"",0));
        int w=(width-35)/4;
        button(this,"Все записи",margin,80,w,()->search("","",false,0));
        button(this,"История ID",margin+w+5,80,w,()->showHistory(selectedId()));
        checkpointButton=button(this,checkpointsOnly?"Точки: да":"Точки: нет",margin+2*(w+5),80,w,()->search(idBox.getValue().trim(),actorBox.getValue().trim(),!checkpointsOnly,0));
        checkpointButton.setTooltip(Tooltip.create(Component.literal("Только сборка, установка, упаковка, разборка, удаление и откат")));
        button(this,"Обновить",margin+3*(w+5),80,width-margin-(margin+3*(w+5)),()->load());
        listX=margin;listY=108;listW=width-20;listH=Math.max(38,height-86-listY);
        if(width>=900){listW=(width-30)*3/5;detailX=listX+listW+10;detailY=listY;detailW=width-detailX-margin;detailH=listH;}else detailW=detailH=0;
        int bottom=height-76;prev=button(this,"←",margin,bottom,30,()->{if(!loading&&!previous.isEmpty()){cursor=previous.pop();load();}});
        nextButton=button(this,"→",margin+35,bottom,30,()->{if(!loading&&next>0){previous.push(cursor);cursor=next;load();}});
        int copyW=(width-105)/3;
        copyId=button(this,"Копировать ID",margin+75,bottom,copyW,()->copy(selectedId()));
        copyEntry=button(this,"Копировать entry",margin+80+copyW,bottom,copyW,()->{var r=chosen();if(r!=null)copy(Long.toString(r.entry()));});
        json=button(this,"Копировать JSON",margin+85+copyW*2,bottom,copyW,this::requestJson);
        w=(width-30)/3;latest=button(this,"Последнее → предмет",margin,height-48,w,()->action(Action.RECOVER,selectedId(),0));
        restore=button(this,"Состояние строки → предмет",margin+w+5,height-48,w,()->{var r=chosen();if(r!=null)action(Action.RECOVER,r.cartId(),r.entry());});
        button(this,"Все действия…",margin+2*(w+5),height-48,width-margin-(margin+2*(w+5)),()->Minecraft.getInstance().setScreen(new Actions(this)));
        button(this,"Результаты",width-90,height-24,80,()->Minecraft.getInstance().setScreen(new Journal(this)));
        updateButtons();
    }
    private EditBox box(int x,int y,int w,int max,String value,String hint){var b=new EditBox(font,x,y,Math.max(30,w),20,Component.literal(hint));b.setMaxLength(max);b.setValue(value);b.setHint(Component.literal(hint));return addRenderableWidget(b);}
    private static Button button(Screen screen,String text,int x,int y,int w,Runnable action){
        int available=Math.max(20,w);var font=Minecraft.getInstance().font;
        String label=font.width(text)>available-10?font.plainSubstrByWidth(text,Math.max(1,available-18))+"…":text;
        var b=Button.builder(Component.literal(label),ignored->action.run()).bounds(x,y,available,20).build();
        // addRenderableWidget is protected; the two screen implementations register their own buttons.
        if(screen instanceof CartHistoryScreen root)root.addRenderableWidget(b);else ((Panel)screen).widget(b);
        b.setTooltip(Tooltip.create(Component.literal(text)));return b;
    }
    private void search(String id,String actor,boolean checkpoints,long before){
        if(loading)return;
        try{if(!id.isBlank())UUID.fromString(id);}catch(IllegalArgumentException e){message("Неверный UUID штуковины",true);return;}
        this.id=id;this.actor=actor;checkpointsOnly=checkpoints;cursor=before;previous.clear();
        idBox.setValue(id);actorBox.setValue(actor);checkpointButton.setMessage(Component.literal(checkpoints?"Точки: да":"Точки: нет"));load();
    }
    public void showHistory(String value){
        if(value==null||value.isBlank()){message("Выберите штуковину или введите её UUID",true);return;}
        if(loading)return;Minecraft.getInstance().setScreen(this);search(value,"",false,0);
    }
    public void load(){
        if(loading)return;loading=true;request=++sequence;status="Загрузка истории…";updateButtons();
        PacketDistributor.sendToServer(new C2SCartHistoryPayload(request,id,cursor,actor,checkpointsOnly));
        if(!session){session=true;action(Action.STATUS,"",0);}
    }
    public void apply(S2CCartHistoryPayload p){
        if(p.kind()==S2CCartHistoryPayload.ERROR){
            if(p.request()!=0&&p.request()!=request)return;if(p.request()!=0)loading=false;else operationRunning=false;
            message(p.message(),true);updateButtons();return;
        }
        if(p.kind()==S2CCartHistoryPayload.PROGRESS||p.kind()==S2CCartHistoryPayload.DONE||p.kind()==S2CCartHistoryPayload.NOTICE){
            if(p.kind()==S2CCartHistoryPayload.PROGRESS)operationRunning=true;
            if(p.kind()==S2CCartHistoryPayload.DONE)operationRunning=false;
            message(p.message(),false);updateButtons();return;
        }
        if(p.kind()!=S2CCartHistoryPayload.PAGE||p.request()!=request||!p.cartId().equals(id))return;
        loading=false;rows.clear();rows.addAll(p.rows());next=p.next();canRestore=p.canRestore();selected=-1;listScroll=0;detailScroll=0;extraDetails="";jsonRequest=-1;
        status=p.message();if(seekEntry>0){for(int i=0;i<rows.size();i++)if(rows.get(i).entry()==seekEntry){select(i);break;}if(selected<0)message("Запись entry="+seekEntry+" не найдена в истории этого ID",true);seekEntry=0;}updateButtons();
    }
    private void updateButtons(){if(prev==null)return;var r=chosen();prev.active=!loading&&!previous.isEmpty();nextButton.active=!loading&&next>0;
        copyId.active=!selectedId().isBlank();copyEntry.active=r!=null;json.active=r!=null&&!loading;
        latest.active=canRestore&&!loading&&!operationRunning&&!selectedId().isBlank();restore.active=canRestore&&!loading&&!operationRunning&&r!=null;
    }
    private S2CCartHistoryPayload.Row chosen(){return selected>=0&&selected<rows.size()?rows.get(selected):null;}
    private String selectedId(){var r=chosen();return r==null?(idBox==null?id:idBox.getValue().trim()):r.cartId();}
    private void action(Action action,String id,long entry){
        try{var payload=new C2SCartActionPayload(action,id,entry);if(action.restorePermission()){operationRunning=true;status="Запрос операции…";updateButtons();}PacketDistributor.sendToServer(payload);}
        catch(IllegalArgumentException e){message("Проверьте UUID и entry: "+e.getMessage(),true);}
    }
    private void requestJson(){var r=chosen();if(r==null)return;jsonRequest=r.entry();status="Загрузка JSON…";PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.entry(),C2SRequestDetailsPayload.Mode.JSON,new long[]{r.entry()},dimension(r)));}
    private static String dimension(S2CCartHistoryPayload.Row r){return r.location().split(" · ",2)[0];}
    public void acceptDetails(S2CLogDetailsPayload p){
        if(p.mode()==C2SRequestDetailsPayload.Mode.DETAILS){var r=chosen();if(r!=null&&r.entry()==p.entryId())extraDetails="\n\n"+p.lines().stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));return;}
        if(p.entryId()!=jsonRequest||p.mode()!=C2SRequestDetailsPayload.Mode.JSON)return;
        long expected=jsonRequest;jsonRequest=-1;String value=p.lines().stream().map(Component::getString).collect(java.util.stream.Collectors.joining("\n"));
        try{var json=com.google.gson.JsonParser.parseString(value).getAsJsonObject();if(!json.has("id")||json.get("id").getAsLong()!=expected)throw new IllegalArgumentException("выбранная запись не найдена");copy(value);}
        catch(RuntimeException e){message("JSON не скопирован: "+value,true);}
    }
    private void select(int index){selected=index;idBox.setValue(rows.get(index).cartId());detailScroll=0;extraDetails="";updateButtons();var r=chosen();PacketDistributor.sendToServer(new C2SRequestDetailsPayload(r.entry(),C2SRequestDetailsPayload.Mode.DETAILS,new long[]{r.entry()},dimension(r)));}
    private void copy(String value){Minecraft.getInstance().keyboardHandler.setClipboard(value);status="Скопировано в буфер обмена";}
    private void message(String value,boolean error){status=value.replace('\n',' ');journal.add((error?"Ошибка: ":"")+value);if(journal.size()>100)journal.removeFirst();}
    private String details(){var r=chosen();return r==null?"Выберите строку.\n\nВернуть саму штуковину: состояние → предмет.\nВсе операции и ввод entry — в «Все действия».\nХод выполнения и ошибки — в «Результаты».":"ID: "+r.cartId()+"\nentry="+r.entry()+"\n"+r.action()+"\n"+r.time()+"\n"+r.actor()+"\n"+r.location()+"\n\n"+r.detail()+extraDetails;}
    public boolean mouseClicked(double x,double y,int button){
        if(button==0&&x>=listX&&x<listX+listW&&y>=listY&&y<listY+listH){int row=listScroll+(int)(y-listY)/38;if(row<rows.size()){select(row);return true;}}
        if(button==0&&detailW>0&&x>=detailX&&x<detailX+detailW&&y>=detailY&&y<detailY+detailH){Minecraft.getInstance().setScreen(new Details(this));return true;}
        return super.mouseClicked(x,y,button);
    }
    public boolean keyPressed(int key,int scan,int mods){if(key==257||key==335){if(actorBox.isFocused()){if(!actorBox.getValue().isBlank())search("",actorBox.getValue().trim(),true,0);}else search(idBox.getValue().trim(),actorBox.getValue().trim(),checkpointsOnly,0);return true;}return super.keyPressed(key,scan,mods);}
    public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(x>=listX&&x<listX+listW&&y>=listY&&y<listY+listH){listScroll=Math.max(0,Math.min(Math.max(0,rows.size()-Math.max(1,listH/38)),listScroll-(int)Math.signum(dy)));return true;}
        if(detailW>0&&x>=detailX&&x<detailX+detailW&&y>=detailY&&y<detailY+detailH){detailScroll=Math.max(0,detailScroll-(int)Math.signum(dy)*3);return true;}return super.mouseScrolled(x,y,dx,dy);
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        g.fill(0,0,width,height,0xEE11151B);g.drawCenteredString(font,title,width/2,9,0xFFFFFF);
        g.fill(listX,listY,listX+listW,listY+listH,0xFF1B232D);g.enableScissor(listX,listY,listX+listW,listY+listH);
        if(rows.isEmpty())g.drawString(font,loading?"Загрузка…":"Нет записей. Измените фильтры или нажмите «Все записи».",listX+5,listY+8,0xAABBCD);
        for(int i=listScroll;i<rows.size();i++){int y=listY+(i-listScroll)*38;if(y>=listY+listH)break;var r=rows.get(i);
            if(i==selected)g.fill(listX,y,listX+listW,y+37,0xFF29496B);
            g.drawString(font,font.plainSubstrByWidth("entry="+r.entry()+"  "+r.time()+"  "+r.action(),listW-12),listX+6,y+4,0xFFFFFF);
            g.drawString(font,font.plainSubstrByWidth(r.actor()+" · "+r.location(),listW-12),listX+6,y+16,0xB5C5D5);
            g.drawString(font,font.plainSubstrByWidth("ID: "+r.cartId()+(r.snapshot()?" · полный снимок":""),listW-12),listX+6,y+27,r.snapshot()?0x8ED5A1:0x8294A6);
        }g.disableScissor();
        if(detailW>0){g.fill(detailX,detailY,detailX+detailW,detailY+detailH,0xFF19212A);var lines=font.split(Component.literal(details()),detailW-12);detailScroll=Math.min(detailScroll,Math.max(0,lines.size()-Math.max(1,detailH/11)));g.enableScissor(detailX,detailY,detailX+detailW,detailY+detailH);
            for(int i=detailScroll;i<lines.size();i++){int y=detailY+5+(i-detailScroll)*11;if(y>=detailY+detailH)break;g.drawString(font,lines.get(i),detailX+6,y,0xDCE6F0);}g.disableScissor();}
        g.drawString(font,font.plainSubstrByWidth((operationRunning?"Выполняется · ":"")+status,width-110),10,height-19,0xC9D7E4);for(var widget:renderables)widget.render(g,mx,my,dt);
    }
    @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){}
    public void onClose(){action(Action.CLOSE,"",0);Minecraft.getInstance().setScreen(parent);}
    public boolean isPauseScreen(){return false;}
    public static CartHistoryScreen owner(Screen current){return current instanceof CartHistoryScreen s?s:current instanceof Panel panel?panel.parent:null;}
    public void confirmation(String message,String command){
        operationRunning=false;updateButtons();try{Minecraft.getInstance().setScreen(new Confirmation(this,message,C2SCartActionPayload.confirmation(command)));}
        catch(IllegalArgumentException e){message("Неверное подтверждение сервера: "+e.getMessage(),true);}
    }
    private abstract static class Panel extends Screen {
        final CartHistoryScreen parent;int scroll;
        Panel(CartHistoryScreen parent,String title){super(Component.literal(title));this.parent=parent;}
        void widget(Button b){addRenderableWidget(b);}
        @Override public void renderBackground(GuiGraphics g,int x,int y,float dt){}
        @Override public void render(GuiGraphics g,int x,int y,float dt){for(var widget:renderables)widget.render(g,x,y,dt);}
        public boolean isPauseScreen(){return false;}
        public void onClose(){Minecraft.getInstance().setScreen(parent);}
        void background(GuiGraphics g){g.fill(0,0,width,height,0xF511151B);g.drawCenteredString(font,title,width/2,12,0xFFFFFF);}
        void text(GuiGraphics g,String text,int top,int bottom){var lines=font.split(Component.literal(text),Math.max(30,width-40));scroll=Math.min(scroll,Math.max(0,lines.size()-Math.max(1,(bottom-top)/12)));g.enableScissor(20,top,width-20,bottom);for(int i=scroll;i<lines.size();i++){int y=top+(i-scroll)*12;if(y>=bottom)break;g.drawString(font,lines.get(i),20,y,0xE4ECF4);}g.disableScissor();}
        public boolean mouseScrolled(double x,double y,double dx,double dy){scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);return true;}
    }
    private static final class Actions extends Panel {
        private EditBox id,entry;private final List<Button> destructive=new ArrayList<>();
        Actions(CartHistoryScreen p){super(p,"Действия со штуковиной");}
        protected void init(){
            destructive.clear();int margin=20,w=width-40,col=(w-5)/2;var selected=parent.chosen();
            id=new EditBox(font,margin,34,w,20,Component.literal("UUID штуковины"));id.setMaxLength(36);id.setValue(parent.selectedId());id.setHint(Component.literal("Полный UUID штуковины"));addRenderableWidget(id);
            entry=new EditBox(font,margin,58,w,20,Component.literal("Номер записи entry"));entry.setMaxLength(19);entry.setValue(selected==null?"":Long.toString(selected.entry()));entry.setHint(Component.literal("entry — номер записи из истории"));addRenderableWidget(entry);
            button(this,"История этого ID",margin,86,col,()->parent.showHistory(id.getValue().trim()));
            button(this,"Перейти к entry",margin+col+5,86,col,()->withEntry(value->{Minecraft.getInstance().setScreen(parent);if(parent.loading){parent.message("Дождитесь загрузки страницы",true);return;}parent.seekEntry=value;parent.search(id.getValue().trim(),"",false,value==Long.MAX_VALUE?0:value+1);}));
            var latest=button(this,"Последнее состояние → предмет",margin,110,col,()->send(Action.RECOVER,0));destructive.add(latest);latest.active=parent.canRestore&&!parent.operationRunning;
            var selectedState=button(this,"Состояние entry → предмет",margin+col+5,110,col,()->withEntry(value->send(Action.RECOVER,value)));destructive.add(selectedState);selectedState.active=parent.canRestore&&!parent.operationRunning;
            var rollback=button(this,"Откатить действия к entry",margin,134,col,()->withEntry(value->send(Action.ROLLBACK,value)));destructive.add(rollback);rollback.active=parent.canRestore&&!parent.operationRunning;
            button(this,"Подробности строки",margin+col+5,134,col,()->Minecraft.getInstance().setScreen(new Details(parent)));
            button(this,"Отменить восстановление",margin,158,col,()->{parent.action(Action.CANCEL_RECOVERY,"",0);onClose();});
            button(this,"Отменить откат действий",margin+col+5,158,col,()->{parent.action(Action.CANCEL_ROLLBACK,"",0);onClose();});
            button(this,"Проверить состояние операции",margin,182,col,()->{parent.action(Action.STATUS,"",0);Minecraft.getInstance().setScreen(new Journal(parent));});
            button(this,"Журнал результатов",margin+col+5,182,col,()->Minecraft.getInstance().setScreen(new Journal(parent)));
            button(this,"Назад",margin,height-28,w,this::onClose);
        }
        private void withEntry(java.util.function.LongConsumer action){try{long value=Long.parseLong(entry.getValue().trim());if(value<=0)throw new IllegalArgumentException("entry должен быть больше нуля");action.accept(value);}catch(IllegalArgumentException e){parent.message("Неверный entry: "+e.getMessage(),true);Minecraft.getInstance().setScreen(new Journal(parent));}}
        private void send(Action action,long entry){parent.action(action,id.getValue().trim(),entry);onClose();}
        public void render(GuiGraphics g,int x,int y,float dt){for(var b:destructive)b.active=parent.canRestore&&!parent.operationRunning;background(g);super.render(g,x,y,dt);}
    }
    private static final class Details extends Panel {
        Details(CartHistoryScreen parent){super(parent,"Подробности выбранной записи");}
        protected void init(){int w=(width-45)/2;button(this,"Копировать текст",20,height-30,w,()->parent.copy(parent.details()));button(this,"Назад",25+w,height-30,w,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float dt){background(g);text(g,parent.details(),36,height-42);super.render(g,x,y,dt);}
    }
    private static final class Journal extends Panel {
        Journal(CartHistoryScreen parent){super(parent,"Ход выполнения и результаты");}
        protected void init(){int w=(width-45)/2;button(this,"Копировать журнал",20,height-30,w,()->parent.copy(String.join("\n\n",parent.journal)));button(this,"Назад",25+w,height-30,w,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float dt){background(g);text(g,parent.journal.isEmpty()?"Операций пока нет.":String.join("\n\n",parent.journal),36,height-42);super.render(g,x,y,dt);}
    }
    private static final class Confirmation extends Panel {
        private final String message;private final C2SCartActionPayload payload;private final long expires=System.currentTimeMillis()+120000;
        private Button confirm;
        Confirmation(CartHistoryScreen parent,String message,C2SCartActionPayload payload){super(parent,"Подтверждение операции");this.message=message;this.payload=payload;}
        protected void init(){int w=(width-45)/2;confirm=button(this,"Подтвердить",20,height-35,w,()->{parent.action(payload.action(),payload.cartId(),payload.entry());Minecraft.getInstance().setScreen(parent);});button(this,"Отмена",25+w,height-35,w,this::onClose);}
        public void render(GuiGraphics g,int x,int y,float dt){background(g);confirm.active=System.currentTimeMillis()<expires;text(g,message+(confirm.active?"":"\n\nПодтверждение истекло. Запросите предпросмотр заново."),40,height-50);super.render(g,x,y,dt);}
        public void onClose(){parent.action(payload.action()==Action.CONFIRM_RECOVERY?Action.CANCEL_RECOVERY:Action.CANCEL_ROLLBACK,"",0);super.onClose();}
    }
}
