package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.*;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import static com.roften.avilixlogger.net.C2SCartActionPayload.Action;

/** The minecart journal is a specialization of the logger, not a second renderer or widget system. */
public final class CartHistoryScreen extends LogViewerScreen {
    private static long sequence;
    private final Screen parent;
    private String id,owner="",actor="",time="",dimension="*",search="";
    private String actionFilter="ALL";
    private boolean loading,canRestore,operationRunning,session;
    private final List<S2CCartHistoryPayload.Row> cartRows=new ArrayList<>();
    private final Deque<Long> previous=new ArrayDeque<>();
    private final List<String> journal=new ArrayList<>();
    private long cursor,next,request,seekEntry;
    private int recoveryMode;
    private Button restore,latest;
    private static final String[] ACTIONS={"ALL","STATE","CART_ASSEMBLE","CART_PLACE","CART_PACK","CART_DISASSEMBLE","CART_REMOVE","CART_STATUS","CARGO","BLOCK_BREAK","BLOCK_PLACE","CART_ROLLBACK"};
    private static final String[] LABELS={"Все действия","События конструкции","Сборка","Установка","Упаковка","Разборка","Удаление","Состояние механизмов","Груз: предметы","Разрушение блока","Установка блока","Восстановление"};
    public CartHistoryScreen(Screen parent,String id){super();this.parent=parent;this.id=id==null?"":id;}
    @Override protected boolean specializedView(){return true;}
    @Override protected void init(){super.init();setJournalTitle("Вагонеточные штуковины");}
    @Override protected void initViewFilters(int top){
        int y=top,h=controlH(),step=h+controlGap(),w=leftPanelW();
        trainBox=filter("ID",id,"Полный UUID штуковины. Можно скопировать из выбранной строки кнопкой ID.",y,36);y+=step;
        planeNameBox=filter("Владелец",owner,"Поиск штуковин по последнему записанному владельцу. В строках указан владелец из события, если он записан. Исполнитель — отдельный фильтр.",y,64);y+=step;
        actorBox=filter("Исполнитель",actor,"Ник того, кто выполнил действие. Сервер не подменяется владельцем или соседним игроком.",y,64);y+=step;
        timeBox=filter("От времени",time,"Как в основном журнале: 30m, 19.10.2026, 03:00, 19.10.2026 03:00; диапазон начало..конец. Пояс: "+logTimeZone,y,96);y+=step;
        radiusBox=filter("Мир",dimension,"* — все миры; иначе полный ID, например minecraft:overworld.",y,128);y+=step;
        btnType=addButton(Component.literal(actionLabel()),b->toggleTypeDropdown(),10,y,w,h);
        btnType.setTooltip(Tooltip.create(Component.literal("Выбрать действие из списка, как в основном журнале. Все фильтры применяются до LIMIT.")));y+=step;
        searchBox=filter("Поиск",search,"Текст, блок, предмет или название действия. Применить или Enter.",y,128);y+=step;
        addButton(Component.literal("Сбросить"),b->clearFilters(),10,y,w,h);y+=step;
        addButton(Component.literal("Применить"),b->applyCustomInputs(),10,y,w,h);y+=step;
        addButton(Component.literal("Штуковина в руке"),b->action(Action.HELD,"",0),10,y,w,h).setTooltip(Tooltip.create(Component.literal("Открыть историю штуковины из основной руки")));
    }
    private EditBox filter(String label,String value,String hint,int y,int max){int w=leftPanelW(),gap=sideInputGap(),bw=sideButtonW(w);
        addButton(Component.literal(label),b->{},10,y,bw,controlH()).setTooltip(Tooltip.create(Component.literal(hint)));
        var box=makeEditBox(10+bw+gap,y,sideBoxW(w,bw,gap),controlH(),Component.literal(label));box.setMaxLength(max);box.setValue(value);box.setTooltip(Tooltip.create(Component.literal(hint)));addRenderableWidget(box);return box;
    }
    @Override protected void initViewModeButtons(int x,int y,int w,int h,int gap){int half=(w-gap)/2;
        addButton(Component.literal("ID"),b->copyValue(selectedId()),x,y,half,h).setTooltip(Tooltip.create(Component.literal("Скопировать полный ID выбранной штуковины")));
        addButton(Component.literal("entry"),b->{var r=chosen();if(r!=null)copyValue(Long.toString(r.entry()));},x+half+gap,y,w-half-gap,h).setTooltip(Tooltip.create(Component.literal("Скопировать номер выбранной записи — entry")));
    }
    @Override protected int initViewActions(int x,int y,int w,int h,int gap){int step=h+gap;
        restore=addButton(Component.literal("Вернуть состояние строки"),b->{var r=chosen();if(r!=null)recover(r.cartId(),r.entry());},x,y,w,h);
        restore.setTooltip(Tooltip.create(Component.literal("Собрать состояние на выбранной строке. Блоки, механизмы, ресурсы и жидкости возвращаются предметом вам. Сначала предпросмотр и подтверждение.")));y+=step;
        latest=addButton(Component.literal("Последнее сохранённое"),b->recover(selectedId(),0),x,y,w,h);
        latest.setTooltip(Tooltip.create(Component.literal("Восстановить последнее записанное состояние этого ID, включая записанные изменения после снимка.")));y+=step;
        addButton(Component.literal(modeLabel()),b->{recoveryMode=(recoveryMode+1)%3;b.setMessage(Component.literal(modeLabel()));},x,y,w,h).setTooltip(Tooltip.create(Component.literal("Способ получения: пропавшая — новая выдача при отсутствии оригинала; в руке — замена вашего предмета; из мира — замена загруженной конструкции предметом. Все способы выдают предмет нажавшему кнопку, без отката действий в мире.")));y+=step;
        int half=(w-gap)/2;
        addButton(Component.literal("Операции…"),b->Minecraft.getInstance().setScreen(new Actions(this)),x,y,half,h);
        addButton(Component.literal("Результаты"),b->Minecraft.getInstance().setScreen(new Journal(this)),x+half+gap,y,w-half-gap,h);y+=step;
        addButton(Component.literal("Основной журнал"),b->onClose(),x,y,w,h);return y+step;
    }
    private String modeLabel(){return switch(recoveryMode){case 1->"Получение: заменить в руке";case 2->"Получение: забрать из мира";default->"Получение: пропавшая";};}
    @Override protected int typeChoiceCount(){return ACTIONS.length;}
    @Override protected int typeChoiceIndex(){return Math.max(0,Arrays.asList(ACTIONS).indexOf(actionFilter));}
    @Override protected Component typeChoiceLabel(int index){return Component.literal(LABELS[index]);}
    @Override protected void typeChoiceSelected(int index){actionFilter=ACTIONS[index];btnType.setMessage(Component.literal(actionLabel()));applyCustomInputs();}
    private String actionLabel(){int i=Arrays.asList(ACTIONS).indexOf(actionFilter);return i<0?"Все действия":LABELS[i];}
    @Override protected void refreshFilterButtonLabels(){}
    @Override protected void clearFilters(){id=owner=actor=time=search="";dimension="*";actionFilter="ALL";cursor=0;previous.clear();refreshCartWidgets();load();}
    @Override protected void applyCustomInputs(){if(loading)return;readFilters();cursor=0;previous.clear();load();}
    private void readFilters(){id=trainBox.getValue().trim();owner=planeNameBox.getValue().trim();actor=actorBox.getValue().trim();time=timeBox.getValue().trim();dimension=radiusBox.getValue().trim();search=searchBox.getValue().trim();}
    private void refreshCartWidgets(){clearWidgets();init();}
    @Override protected void sendPage(C2SRequestPagePayload.Nav nav){if(loading)return;if(nav==C2SRequestPagePayload.Nav.NEXT){if(next==0)return;previous.push(cursor);cursor=next;}else if(nav==C2SRequestPagePayload.Nav.PREV){if(previous.isEmpty())return;cursor=previous.pop();}else if(nav==C2SRequestPagePayload.Nav.FIRST){readFilters();cursor=0;previous.clear();}load();}
    public void showHistory(String value){if(value==null||value.isBlank()){message("Введите ID или выберите строку",true);return;}if(loading)return;id=value;owner=actor=time=search="";dimension="*";actionFilter="ALL";cursor=0;previous.clear();Minecraft.getInstance().setScreen(this);refreshCartWidgets();load();}
    public void load(){if(loading)return;
        try{if(!id.isBlank())UUID.fromString(id);GuiTimeFilter.parse(time,java.time.ZonedDateTime.now(logTimeZone));
            request=++sequence;PacketDistributor.sendToServer(new C2SCartHistoryPayload(request,id,cursor,actor,false,owner,time,actionFilter,dimension,search,configuredPageSize()));
            loading=true;setJournalTitle("Штуковины · загрузка…");updateButtons();if(!session){session=true;action(Action.STATUS,"",0);}
        }catch(IllegalArgumentException e){message(e.getMessage(),true);}
    }
    public void apply(S2CCartHistoryPayload p){
        if(p.kind()==S2CCartHistoryPayload.ERROR){if(p.request()!=0&&p.request()!=request)return;if(p.request()!=0)loading=false;else operationRunning=false;message(p.message(),true);updateButtons();return;}
        if(p.kind()==S2CCartHistoryPayload.PROGRESS||p.kind()==S2CCartHistoryPayload.DONE||p.kind()==S2CCartHistoryPayload.NOTICE){if(p.kind()==S2CCartHistoryPayload.PROGRESS)operationRunning=true;if(p.kind()==S2CCartHistoryPayload.DONE)operationRunning=false;message(p.message(),false);updateButtons();return;}
        if(p.kind()!=S2CCartHistoryPayload.PAGE||p.request()!=request||!p.cartId().equals(id))return;
        loading=false;cartRows.clear();cartRows.addAll(p.rows());next=p.next();canRestore=p.canRestore();var display=new ArrayList<LogRow>();
        for(var r:cartRows){String[] loc=r.location().split(" · ",2);String[] xyz=loc.length==2?loc[1].split(" "):new String[0];int x=0,y=0,z=0;try{if(xyz.length==3){x=Integer.parseInt(xyz[0]);y=Integer.parseInt(xyz[1]);z=Integer.parseInt(xyz[2]);}}catch(NumberFormatException ignored){}
            var line=Component.literal(r.time()+" · ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.literal("Владелец: "+r.owner()).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" · ID: "+r.cartId().substring(0,8)+"… · entry="+r.entry()+" · ").withStyle(ChatFormatting.GRAY))
                .append(Component.literal(r.action()).withStyle(s->s.withColor(r.color())))
                .append(Component.literal(r.effect().isBlank()?"":" → "+r.effect()).withStyle(ChatFormatting.YELLOW))
                .append(Component.literal(" · "+r.actor()+" · "+r.location()).withStyle(ChatFormatting.GRAY));
            display.add(LogRow.single(r.entry(),loc[0],x,y,z,line));
        }
        super.apply(new S2CLogPagePayload("Вагонеточные штуковины",previous.size()+1,!previous.isEmpty(),next>0,display));
        setDetails(List.of(Component.literal(p.message()),Component.literal("Выберите строку → «Вернуть состояние строки». Получатель предмета — вы."),Component.literal("ID и entry копируются кнопками справа. Поиск применяется ко всему журналу.")));
        if(seekEntry>0){selectEntry(seekEntry);if(selectedRow()==null)message("Запись entry="+seekEntry+" не найдена у этого ID",true);seekEntry=0;}
        updateButtons();
    }
    @Override protected void updateButtons(){super.updateButtons();if(restore!=null)restore.active=canRestore&&!loading&&!operationRunning&&chosen()!=null;if(latest!=null)latest.active=canRestore&&!loading&&!operationRunning&&!selectedId().isBlank();}
    private S2CCartHistoryPayload.Row chosen(){var selected=selectedRow();if(selected==null)return null;for(var r:cartRows)if(r.entry()==selected.id())return r;return null;}
    private String selectedId(){var r=chosen();return r==null?(trainBox==null?id:trainBox.getValue().trim()):r.cartId();}
    @Override protected void selectionChanged(LogRow row){var r=chosen();if(r!=null)setDetails(List.of(Component.literal(r.detail()),Component.literal("Исполнитель: "+r.actor()),Component.literal(r.location())));updateButtons();}
    private void recover(String id,long entry){action(recoveryMode==1?Action.RESTORE_HELD:recoveryMode==2?Action.RESTORE_WORLD:Action.RECOVER,id,entry);}
    private void action(Action action,String id,long entry){try{var payload=new C2SCartActionPayload(action,id,entry);if(action.restorePermission()){operationRunning=true;message("Готовлю предпросмотр…",false);updateButtons();}PacketDistributor.sendToServer(payload);}catch(IllegalArgumentException e){message("Проверьте ID и entry: "+e.getMessage(),true);}}
    private void message(String value,boolean error){journal.add((error?"Ошибка: ":"")+value);if(journal.size()>100)journal.removeFirst();setDetails(List.of(Component.literal(value).withStyle(error?ChatFormatting.RED:ChatFormatting.GRAY)));}
    public void acceptDetails(S2CLogDetailsPayload p){super.applyDetails(p);}
    public void confirmation(String message,String command){operationRunning=false;updateButtons();try{Minecraft.getInstance().setScreen(new Confirmation(this,message,C2SCartActionPayload.confirmation(command)));}catch(IllegalArgumentException e){message(e.getMessage(),true);}}
    @Override public void onClose(){action(Action.CLOSE,"",0);Minecraft.getInstance().setScreen(parent);}
    public static CartHistoryScreen owner(Screen screen){if(LogViewerScreen.activeViewer(screen) instanceof CartHistoryScreen s)return s;if(screen instanceof Panel p)return p.parent;if(screen instanceof LoggerFunctionsScreen f)return owner(f.parentScreen());return null;}
    private abstract static class Panel extends LogViewerScreen {
        final CartHistoryScreen parent;final String heading;int scroll;
        Panel(CartHistoryScreen parent,String heading){this.parent=parent;this.heading=heading;}
        @Override protected boolean specializedView(){return true;}
        @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
        @Override public boolean mouseScrolled(double x,double y,double dx,double dy){scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);return true;}
        protected void text(GuiGraphics g,String value,int top,int bottom){int max=Math.max(0,font.split(Component.literal(value),Math.max(20,Math.round((width-40)/logTextScale()))).size()-Math.max(1,(bottom-top)/detailLineH()));scroll=Math.min(scroll,max);renderThemedPanel(g,heading,value,top,bottom,scroll);}
        protected void back(){addButton(Component.literal("Назад"),b->onClose(),20,height-30,width-40,controlH());}
    }
    private static final class Actions extends Panel {
        private EditBox id,entry;
        Actions(CartHistoryScreen parent){super(parent,"Операции со штуковиной");}
        @Override protected void init(){int x=20,y=30,w=width-40,h=controlH(),step=h+controlGap();var selected=parent.chosen();
            id=makeEditBox(x,y,w,h,Component.literal("ID"));id.setMaxLength(36);id.setValue(parent.selectedId());id.setTooltip(Tooltip.create(Component.literal("Полный ID штуковины")));addRenderableWidget(id);y+=step;
            entry=makeEditBox(x,y,w,h,Component.literal("entry"));entry.setMaxLength(19);entry.setValue(selected==null?"":Long.toString(selected.entry()));entry.setHint(Component.literal("entry — номер строки"));addRenderableWidget(entry);y+=step;
            addButton(Component.literal("Открыть историю ID"),b->parent.showHistory(id.getValue().trim()),x,y,w,h);y+=step;
            addButton(Component.literal("Перейти к entry"),b->withEntry(e->{if(parent.loading)return;parent.id=id.getValue().trim();parent.owner=parent.actor=parent.time=parent.search="";parent.dimension="*";parent.actionFilter="ALL";parent.seekEntry=e;parent.cursor=e==Long.MAX_VALUE?0:e+1;parent.previous.clear();Minecraft.getInstance().setScreen(parent);parent.load();}),x,y,w,h);y+=step;
            addButton(Component.literal("Последнее состояние → мне в инвентарь"),b->{parent.recover(id.getValue().trim(),0);onClose();},x,y,w,h);y+=step;
            addButton(Component.literal("Состояние entry → мне в инвентарь"),b->withEntry(e->{parent.recover(id.getValue().trim(),e);onClose();}),x,y,w,h);y+=step;
            addButton(Component.literal("Отменить подготовку восстановления"),b->{parent.action(Action.CANCEL_RECOVERY,"",0);onClose();},x,y,w,h);y+=step;
            addButton(Component.literal("Проверить состояние операции"),b->{parent.action(Action.STATUS,"",0);Minecraft.getInstance().setScreen(new Journal(parent));},x,y,w,h);y+=step;
            addButton(Component.literal("Дополнительно: откат действий в мире"),b->withEntry(e->{parent.action(Action.ROLLBACK,id.getValue().trim(),e);onClose();}),x,y,w,h).setTooltip(Tooltip.create(Component.literal("Отдельная старая операция: изменяет мир и связанные передачи. Для возврата самой штуковины используйте кнопки состояния выше.")));y+=step;
            addButton(Component.literal("Отменить откат действий"),b->{parent.action(Action.CANCEL_ROLLBACK,"",0);onClose();},x,y,w,h);back();
        }
        private void withEntry(java.util.function.LongConsumer action){try{long e=Long.parseLong(entry.getValue().trim());if(e<=0)throw new IllegalArgumentException();action.accept(e);}catch(IllegalArgumentException e){parent.message("Введите положительный entry из строки журнала",true);Minecraft.getInstance().setScreen(new Journal(parent));}}
        @Override public void render(GuiGraphics g,int x,int y,float dt){text(g,"",24,24);for(var widget:renderables)widget.render(g,x,y,dt);}
    }
    private static final class Journal extends Panel {
        Journal(CartHistoryScreen parent){super(parent,"Ход выполнения и результаты");}
        @Override protected void init(){back();}
        @Override public void render(GuiGraphics g,int x,int y,float dt){text(g,parent.journal.isEmpty()?"Операций пока нет":String.join("\n\n",parent.journal),30,height-40);for(var widget:renderables)widget.render(g,x,y,dt);}
    }
    private static final class Confirmation extends Panel {
        private final String message;private final C2SCartActionPayload payload;private final long expires=System.currentTimeMillis()+120000;private Button confirm;
        Confirmation(CartHistoryScreen parent,String message,C2SCartActionPayload payload){super(parent,"Подтверждение восстановления");this.message=message;this.payload=payload;}
        @Override protected void init(){int w=(width-45)/2;confirm=addButton(Component.literal("Подтвердить"),b->{parent.action(payload.action(),payload.cartId(),payload.entry());Minecraft.getInstance().setScreen(parent);},20,height-30,w,controlH());addButton(Component.literal("Отмена"),b->onClose(),25+w,height-30,w,controlH());}
        @Override public void render(GuiGraphics g,int x,int y,float dt){confirm.active=System.currentTimeMillis()<expires;text(g,message+(confirm.active?"":"\nПодтверждение истекло"),30,height-42);for(var widget:renderables)widget.render(g,x,y,dt);}
        @Override public void onClose(){parent.action(payload.action()==Action.CONFIRM_RECOVERY?Action.CANCEL_RECOVERY:Action.CANCEL_ROLLBACK,"",0);super.onClose();}
    }
}
