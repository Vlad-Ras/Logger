package com.roften.avilixlogger.client.gui;
import com.roften.avilixlogger.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
/** Forms over the same logger command handlers; output and async replies stay inside this GUI. */
public final class LoggerFunctionsScreen extends LogViewerScreen {
    private static long sequence;private long request;
    private final Screen parent;private final List<String> output=new ArrayList<>();
    private LoggerGuiOperation operation=LoggerGuiOperation.HELP;
    private String group="Основное",firstValue="",secondValue="";private EditBox first,second;private int scroll,choiceScroll;
    private boolean armed,we,targetBlock;private String armedCommand="";private final List<EditBox> searchInputs=new ArrayList<>();private final String[] searchValues={"","","","","",""};
    private static final String[] SEARCH_KEYS={"time","radius","player","dimension","mode","owner"};
    private static final String[] SEARCH_LABELS={"Время / дата","Радиус","Игрок","Мир","Режим","Владелец самолёта"};
    private static final String[] SEARCH_HINTS={"30m, 2h, 19.10.2026, 03:00 или 19.10.2026 03:00","Радиус в блоках, 0 — без ограничения","Ник исполнителя действия","minecraft:overworld, minecraft:the_nether; пусто — текущий мир","all, grief, theft, combat, planes","Ник владельца самолёта; поиск штуковин по владельцу — на их отдельной странице"};
    private static final List<String> GROUPS=List.of("Основное","Штуковины","Откаты","Пресеты","Рюкзаки","Самолёты","Настройки");
    public LoggerFunctionsScreen(Screen parent){this.parent=parent;output.addAll(LoggerGuiOperation.help());}
    public Screen parentScreen(){return parent;}
    @Override protected boolean specializedView(){return true;}
    @Override protected void init(){int x=10,y=30,w=leftPanelW(),h=controlH(),step=h+controlGap();
        btnType=addButton(Component.literal("Раздел: "+group),b->toggleTypeDropdown(),x,y,w,h);btnType.setTooltip(Tooltip.create(Component.literal("Выбрать раздел функций из списка")));y+=step;
        if(group.equals("Штуковины")){addButton(Component.literal("Открыть журнал штуковин"),b->{var cart=new CartHistoryScreen(this,"");Minecraft.getInstance().setScreen(cart);cart.load();},x,y,w,h);}
        else {var choices=Arrays.stream(LoggerGuiOperation.values()).filter(o->o.group.equals(group)).toList();int visible=Math.max(1,(height-70-y)/step);
            choiceScroll=Math.min(choiceScroll,Math.max(0,choices.size()-visible));
            for(var op:choices.subList(choiceScroll,Math.min(choices.size(),choiceScroll+visible))){addButton(Component.literal(op.label),b->{capture();operation=op;firstValue=secondValue="";armed=false;clearWidgets();init();},x,y,w,h).setTooltip(Tooltip.create(Component.literal(op.hint.isBlank()?op.label:op.hint)));y+=step;}
        }
        addButton(Component.literal("Назад"),b->onClose(),x,height-30,w,h);
        int fx=rightPanelX(),fw=rightPanelW();y=30;
        searchInputs.clear();
        if(operation.searchForm())for(int i=0;i<SEARCH_KEYS.length;i++){
            var box=makeEditBox(fx,y,fw,h,Component.literal(SEARCH_LABELS[i]));box.setMaxLength(96);box.setHint(Component.literal(SEARCH_LABELS[i]));box.setValue(searchValues[i]);box.setTooltip(Tooltip.create(Component.literal(SEARCH_HINTS[i])));addRenderableWidget(box);searchInputs.add(box);y+=step;
        }
        if(!operation.first.isEmpty()){first=makeEditBox(fx,y,fw,h,Component.literal(operation.first));first.setMaxLength(1024);first.setHint(Component.literal(operation.searchForm()?"Доп. флаги (необязательно)":operation.first));first.setValue(firstValue);first.setTooltip(Tooltip.create(Component.literal(operation.first+". "+operation.hint)));addRenderableWidget(first);y+=step;}else first=null;
        if(!operation.second.isEmpty()){second=makeEditBox(fx,y,fw,h,Component.literal(operation.second));second.setMaxLength(1024);second.setHint(Component.literal(operation.second));second.setValue(secondValue);second.setTooltip(Tooltip.create(Component.literal(operation.second+". "+operation.hint)));addRenderableWidget(second);y+=step;}else second=null;
        if(operation.searchForm()){
            addButton(Component.literal("WorldEdit: "+(we?"да":"нет")),b->{we=!we;b.setMessage(Component.literal("WorldEdit: "+(we?"да":"нет")));},fx,y,fw,h).setTooltip(Tooltip.create(Component.literal("Использовать ваше выделение WorldEdit вместо радиуса")));y+=step;
            addButton(Component.literal("Блок под прицелом: "+(targetBlock?"да":"нет")),b->{targetBlock=!targetBlock;b.setMessage(Component.literal("Блок под прицелом: "+(targetBlock?"да":"нет")));},fx,y,fw,h);y+=step;
        }
        addButton(Component.literal(armed?"Ещё раз: подтвердить":operation.confirmation?"Подтвердить операцию":"Выполнить"),b->execute(),fx,y,fw,h).setTooltip(Tooltip.create(Component.literal(operation.hint.isBlank()?operation.label:operation.hint)));y+=step;
        addButton(Component.literal("Справка"),b->{operation=LoggerGuiOperation.HELP;output.clear();output.addAll(LoggerGuiOperation.help());scroll=0;clearWidgets();init();},fx,y,fw,h);y+=step;
        addButton(Component.literal("Скопировать результат"),b->copyValue(String.join("\n",output)),fx,y,fw,h);y+=step;
        addButton(Component.literal("Очистить результат"),b->{output.clear();scroll=0;},fx,y,fw,h);y+=step;
        addButton(Component.literal("Настройки интерфейса"),b->openSettings(),fx,y,fw,h);
    }
    @Override protected int typeChoiceCount(){return GROUPS.size();}
    @Override protected int typeChoiceIndex(){return GROUPS.indexOf(group);}
    @Override protected Component typeChoiceLabel(int index){return Component.literal(GROUPS.get(index));}
    @Override protected void typeChoiceSelected(int index){capture();group=GROUPS.get(index);choiceScroll=0;clearWidgets();init();}
    private void capture(){for(int i=0;i<searchInputs.size();i++)searchValues[i]=searchInputs.get(i).getValue();if(first!=null)firstValue=first.getValue();if(second!=null)secondValue=second.getValue();}
    private void execute(){capture();if(operation==LoggerGuiOperation.HELP){output.clear();output.addAll(LoggerGuiOperation.help());scroll=0;return;}
        try{
            String data=formData(),concrete=operation.command(firstValue,data);
            if(operation.confirmation&&(!armed||!armedCommand.equals(concrete))){armed=true;armedCommand=concrete;output.add("Подтвердите: "+operation.label+(firstValue.isBlank()?"":" · "+firstValue)+(secondValue.isBlank()?"":" · "+secondValue)+". Нажмите «Ещё раз: подтвердить».");clearWidgets();init();return;}
            request=++sequence;PacketDistributor.sendToServer(new C2SLoggerFunctionPayload(request,operation,firstValue,data));output.clear();output.add(operation.label+" — запрос отправлен");scroll=0;armed=false;
        }catch(IllegalArgumentException e){output.add(e.getMessage());}
    }
    private String formData(){if(!operation.searchForm())return secondValue;var form=new com.google.gson.JsonObject();for(int i=0;i<SEARCH_KEYS.length;i++)form.addProperty(SEARCH_KEYS[i],searchValues[i]);form.addProperty("we",we);form.addProperty("block",targetBlock);return form.toString();}
    public void accept(S2CLoggerFunctionPayload p){if(p.request()!=request)return;output.add(p.text());if(output.size()>300)output.removeFirst();}
    @Override public boolean keyPressed(int key,int scan,int mods){if(key==257||key==335){execute();return true;}return super.keyPressed(key,scan,mods);}
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){if(choiceDropdownOpen())return super.mouseScrolled(x,y,dx,dy);if(x<10+leftPanelW()){choiceScroll=Math.max(0,choiceScroll-(int)Math.signum(dy));capture();clearWidgets();init();}else scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);return true;}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){g.drawCenteredString(font,Component.literal("Все функции · "+operation.label).withStyle(net.minecraft.ChatFormatting.GOLD),width/2,8,0xFFFFFF);
        int left=listLeftX(),right=rightPanelX()-10,top=32,bottom=height-30,h=detailLineH();var lines=new ArrayList<net.minecraft.util.FormattedCharSequence>();for(String text:output)lines.addAll(font.split(Component.literal(text),Math.max(20,Math.round((right-left)/logTextScale()))));
        scroll=Math.min(scroll,Math.max(0,lines.size()-Math.max(1,(bottom-top)/h)));g.enableScissor(left,top,right,bottom);
        for(int i=scroll;i<lines.size();i++){int y=top+(i-scroll)*h;if(y>=bottom)break;g.pose().pushPose();g.pose().translate(left,y,0);g.pose().scale(logTextScale(),logTextScale(),1);g.drawString(font,lines.get(i),0,0,0xFFFFFF,false);g.pose().popPose();}g.disableScissor();for(var widget:renderables)widget.render(g,mx,my,dt);renderChoiceDropdown(g,mx,my);
    }
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
}
