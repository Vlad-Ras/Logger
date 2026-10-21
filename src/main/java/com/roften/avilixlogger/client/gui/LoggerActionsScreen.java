package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.LoggerActionCatalog;
import com.roften.avilixlogger.net.C2SLoggerActionPayload;
import com.roften.avilixlogger.net.S2CLoggerActionResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;

/** The command catalogue as forms, using the journal's shared widgets and bounded result pane. */
public final class LoggerActionsScreen extends Screen {
    private static java.lang.ref.WeakReference<LoggerActionsScreen> current=new java.lang.ref.WeakReference<>(null);
    private static long sequence;
    private final Screen parent;
    private final List<LoggerActionCatalog.Action> allActions;
    private List<LoggerActionCatalog.Action> actions;
    private final List<String> groups;
    private String group="Все";
    private static final int ACTION_ROW_H=28;
    private List<Component> actionLabels=List.of();
    private List<List<net.minecraft.util.FormattedCharSequence>> actionCaptions=List.of();
    private List<net.minecraft.util.FormattedCharSequence> heading=List.of();
    private final List<EditBox> inputs=new ArrayList<>();
    private record JournalLine(Component text,int weight) {}
    private final Deque<JournalLine> journal=new ArrayDeque<>();
    private final WrappedLogText resultLayout=new WrappedLogText(),hintLayout=new WrappedLogText();
    private int selected,listScroll,textScroll,journalChars;
    private long lastRequest;
    private final long firstRequest=sequence+1;
    private boolean results;
    private List<Component> resultLines=List.of(Component.literal("Результаты появятся здесь после выполнения действия."));
    private List<String> values=List.of();
    private Button execute;
    public LoggerActionsScreen(Screen parent){
        super(Component.literal("Действия логгера"));this.parent=parent;
        allActions=LoggerActionCatalog.ACTIONS.stream().filter(this::available).toList();actions=allActions;
        var categories=new ArrayList<String>();categories.add("Все");categories.addAll(allActions.stream().map(LoggerActionCatalog.Action::group).distinct().toList());groups=List.copyOf(categories);
        if(!actions.isEmpty())values=actions.getFirst().fields().stream().map(LoggerActionCatalog.Field::initial).toList();
        current=new java.lang.ref.WeakReference<>(this);
    }
    private boolean available(LoggerActionCatalog.Action action){
        var connection=Minecraft.getInstance().getConnection();if(connection==null)return false;
        var node=connection.getCommands().getRoot().getChild("log");if(node==null)return false;
        for(String word:action.template().substring(3).trim().split(" ")){
            if(word.isEmpty())continue;if(word.startsWith("--"))break;
            var child=node.getChild(word);
            if(child==null&&word.startsWith("{"))child=node.getChildren().stream().filter(com.mojang.brigadier.tree.ArgumentCommandNode.class::isInstance).findFirst().orElse(null);
            if(child==null)return false;node=child;
        }
        return true;
    }
    private int divider(){return Math.min(230,Math.max(135,width/3));}
    private double scale(){return LogViewerScreen.sharedControlScale(width,height);}
    private Button button(String text,int x,int y,int w,Runnable action){return addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,y,w,20,Component.literal(text),b->action.run(),this::scale));}
    private void remember(){if(!inputs.isEmpty())values=inputs.stream().map(EditBox::getValue).toList();}
    private void refresh(){remember();clearWidgets();init();}
    protected void init(){
        inputs.clear();
        actionLabels=actions.stream().map(a->(Component)Component.literal(a.label())).toList();
        actionCaptions=actionLabels.stream().map(label->font.split(label,divider()-26)).toList();
        button(results?"К действиям":"Результаты",10,30,110,()->{results=!results;textScroll=0;refresh();});
        button("Справка",125,30,85,()->minecraft.setScreen(new LoggerGuideScreen(this)));
        var category=button(group,215,30,Math.min(105,width-225),()->{
            group=groups.get((groups.indexOf(group)+1)%groups.size());
            actions=group.equals("Все")?allActions:allActions.stream().filter(a->a.group().equals(group)).toList();
            selected=0;listScroll=0;textScroll=0;
            values=actions.isEmpty()?List.of():actions.getFirst().fields().stream().map(LoggerActionCatalog.Field::initial).toList();
            inputs.clear();clearWidgets();init();
        });
        category.active=groups.size()>1;category.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.literal("Категория действий")));
        button("Назад",10,height-28,90,this::onClose);
        if(results){button("Копировать",105,height-28,105,()->minecraft.keyboardHandler.setClipboard(journal.stream().map(line->line.text().getString()).collect(java.util.stream.Collectors.joining("\n\n"))));return;}
        if(actions.isEmpty())return;
        int x=divider()+16,w=width-x-10,y=85;
        var action=actions.get(selected);
        heading=font.split(Component.literal(action.group()+" · "+action.label()),w);
        for(int i=0;i<action.fields().size();i++){
            var input=new LoggerGuiWidgets.ScaledEditBox(font,x,y+11,w,20,Component.literal(action.fields().get(i).label()),this::scale);
            input.setMaxLength(512);input.setValue(i<values.size()?values.get(i):"");addRenderableWidget(input);inputs.add(input);y+=37;
        }
        execute=button("Выполнить",x,height-28,w,this::execute);
    }
    private void execute(){
        if(actions.isEmpty())return;remember();var action=actions.get(selected);
        try{action.command(values);lastRequest=++sequence;append("→ "+action.label());PacketDistributor.sendToServer(new C2SLoggerActionPayload(lastRequest,action.id(),values));results=true;textScroll=0;refresh();}
        catch(IllegalArgumentException e){append(e.getMessage());results=true;refresh();}
    }
    private void append(String message){append(Component.literal(message),message.length());}
    private void append(Component message,int weight){
        journal.addLast(new JournalLine(message,weight));journalChars+=weight;
        while(journal.size()>100||journalChars>64000&&journal.size()>1)journalChars-=journal.removeFirst().weight();
        var lines=new ArrayList<Component>();for(var line:journal){lines.add(line.text());lines.add(Component.empty());}
        resultLines=List.copyOf(lines);resultLayout.clear();
    }
    public static void accept(S2CLoggerActionResultPayload payload){
        var screen=current.get();
        if(screen!=null&&payload.request()>=screen.firstRequest&&payload.request()<=screen.lastRequest)
            screen.append(payload.component(),payload.message().length()+payload.links().stream().mapToInt(link->link.value().length()+link.action().length()).sum());
    }
    public static void clear(){current.clear();}
    public boolean mouseClicked(double x,double y,int button){
        if(results&&button==0&&x>=13&&x<width-10&&y>=60&&y<height-36){
            var lines=resultLayout.get(font,resultLines,width-28);int index=textScroll+(int)(y-60)/11;
            if(index>=0&&index<lines.size()){
                var style=font.getSplitter().componentStyleAtWidth(lines.get(index),(int)x-13);
                if(style!=null&&style.getClickEvent()!=null){
                    var click=style.getClickEvent();
                    if(click.getAction()==net.minecraft.network.chat.ClickEvent.Action.RUN_COMMAND||click.getAction()==net.minecraft.network.chat.ClickEvent.Action.SUGGEST_COMMAND){
                        var form=LoggerActionCatalog.linkedForm(allActions,click.getValue());
                        if(form!=null){
                            group=form.action().group();actions=allActions.stream().filter(a->a.group().equals(group)).toList();
                            selected=actions.indexOf(form.action());listScroll=0;values=form.values();results=false;
                            inputs.clear();clearWidgets();init();textScroll=0;return true;
                        }
                    }
                    if(handleComponentClicked(style))return true;
                }
            }
        }
        if(!results&&button==0&&x>=10&&x<divider()&&y>=60&&y<height-36){int index=listScroll+(int)(y-60)/ACTION_ROW_H;if(index<actions.size()){remember();selected=index;values=actions.get(index).fields().stream().map(LoggerActionCatalog.Field::initial).toList();inputs.clear();clearWidgets();init();hintLayout.clear();textScroll=0;return true;}}
        return super.mouseClicked(x,y,button);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(!results&&x<divider()){listScroll=Math.max(0,Math.min(Math.max(0,actions.size()-Math.max(1,(height-96)/ACTION_ROW_H)),listScroll-(int)Math.signum(dy)*3));return true;}
        textScroll=Math.max(0,textScroll-(int)Math.signum(dy)*3);return true;
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        LoggerGuiWidgets.beginFrame(g,width,height);
        g.drawCenteredString(font,title,width/2,10,0xFFAA00);
        if(results)text(g,resultLines,resultLayout,10,60,width-20,height-96);
        else if(!actions.isEmpty()){
            g.fill(8,58,divider()+3,height-35,0x44000000);g.enableScissor(10,60,divider(),height-36);
            for(int i=listScroll;i<actions.size();i++){
                int y=60+(i-listScroll)*ACTION_ROW_H;if(y>=height-36)break;
                if(i==selected)g.fill(10,y,divider(),y+ACTION_ROW_H-1,0x55336699);
                var caption=actionCaptions.get(i);for(int line=0;line<Math.min(2,caption.size());line++)g.drawString(font,caption.get(line),16,y+4+line*10,0xFFFFFF);
            }
            g.disableScissor();var action=actions.get(selected);int x=divider()+16;
            for(int i=0;i<Math.min(2,heading.size());i++)g.drawString(font,heading.get(i),x,62+i*10,0xFFAA00);
            for(int i=0;i<action.fields().size();i++)g.drawString(font,font.plainSubstrByWidth(action.fields().get(i).label(),width-x-10),x,85+i*37,0xAAAAAA);
            int top=90+action.fields().size()*37;
            text(g,action.hint(),hintLayout,x,top,width-x-10,Math.max(0,height-38-top));
        }else g.drawString(font,"Доступных действий нет: проверьте права на сервере.",10,65,0xAAAAAA);
        for(var widget:renderables)widget.render(g,mx,my,dt);
        if(!results&&mx>=10&&mx<divider()&&my>=60&&my<height-36){int index=listScroll+(my-60)/ACTION_ROW_H;if(index<actionLabels.size())g.renderTooltip(font,actionLabels.get(index),mx,my);}
    }
    private void text(GuiGraphics g,Object value,WrappedLogText layout,int x,int y,int w,int h){
        if(w<10||h<10)return;var lines=layout.get(font,value,w-8);int visible=Math.max(1,h/11);textScroll=Math.min(textScroll,Math.max(0,lines.size()-visible));
        g.fill(x-2,y-2,x+w,y+h,0x44000000);g.enableScissor(x,y,x+w,y+h);
        for(int i=textScroll;i<Math.min(lines.size(),textScroll+visible);i++)g.drawString(font,lines.get(i),x+3,y+(i-textScroll)*11,0xFFFFFF);g.disableScissor();
    }
    public void resize(Minecraft minecraft,int width,int height){remember();super.resize(minecraft,width,height);}
    public void renderBackground(GuiGraphics g,int x,int y,float dt){}
    public boolean isPauseScreen(){return false;}
    public void onClose(){remember();minecraft.setScreen(parent);}
}
