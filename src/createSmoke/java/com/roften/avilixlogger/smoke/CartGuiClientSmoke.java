package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.client.gui.CartHistoryScreen;
import com.roften.avilixlogger.net.S2CCartHistoryPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.*;

/** Dev-only real client rendering. Synthetic rows; no auth bypass or server operations. */
@EventBusSubscriber(modid="avilixlogger",value=Dist.CLIENT)
public final class CartGuiClientSmoke {
    private static CartHistoryScreen root;
    private static int ticks,stage,readyTicks,waitingTicks;
    private static boolean finished;
    private static final String ID="51000000-0000-0000-0000-000000000001";
    private static final List<String> FILES=List.of("cart-gui-history.png","cart-gui-actions.png","cart-gui-confirm.png","cart-gui-results.png","logger-actions.png","logger-action-results.png","logger-guide.png","logger-main.png","logger-capture-types.png");
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("avilixlogger.guiSmoke")||finished)return;
        var mc=Minecraft.getInstance();
        try{
            if(root==null){
                if(mc.getOverlay()==null&&mc.screen instanceof net.minecraft.client.gui.screens.AccessibilityOnboardingScreen){
                    mc.options.onboardingAccessibilityFinished();mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());return;
                }
                if(++waitingTicks%200==0)System.out.println("GUI_SMOKE_WAIT: screen="+(mc.screen==null?"null":mc.screen.getClass().getName())+" overlay="+(mc.getOverlay()==null?"none":mc.getOverlay().getClass().getName()));
                if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)||mc.getOverlay()!=null||++readyTicks<40)return;
                root=new CartHistoryScreen(mc.screen,"");mc.setScreen(root);
                var rows=new ArrayList<S2CCartHistoryPayload.Row>();for(int i=0;i<12;i++)rows.add(new S2CCartHistoryPayload.Row(ID,100-i,"18.10.2026 23:50:00","Штуковина: упаковка","MaxKuz","minecraft:overworld · -1775 38 -2726","Полный снимок: 125 блоков. Хранилища предметов и жидкости сохранены.",true,true));
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,0,"",88,true,"Все операции доступны через GUI","",rows));
                var selected=CartHistoryScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.setInt(root,0);
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.DONE,0,"",0,false,"Штуковина выдана в свободный слот. entry=100. Сохранены блоки, компоненты предметов и жидкости.","",List.of()));
                var jsonRequest=CartHistoryScreen.class.getDeclaredField("jsonRequest");jsonRequest.setAccessible(true);
                mc.keyboardHandler.setClipboard("GUI smoke sentinel");jsonRequest.setLong(root,100);
                root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":99}"))));
                check(mc.keyboardHandler.getClipboard().equals("GUI smoke sentinel"),"neighbour JSON copied as selected entry");
                jsonRequest.setLong(root,100);root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":100}"))));
                check(mc.keyboardHandler.getClipboard().equals("{\"id\":100}"),"selected JSON not copied");
                layout(root,mc);ticks=0;return;
            }
            if(++ticks<15)return;ticks=0;
            if(stage<FILES.size()){
                Screenshot.grab(mc.gameDirectory,FILES.get(stage),mc.getMainRenderTarget(),message->System.out.println("CART_GUI_SCREENSHOT: "+message.getString()));
                stage++;
                if(stage==1)mc.setScreen(panel("Actions"));
                else if(stage==2)root.confirmation("Вернуть саму штуковину в предмет: "+ID+". entry=100; блоков: 125. Основа: снимок entry=80, применено изменений состояния: 12. Хранилищ предметов: 4, предметов: 128; баков: 2, жидкости: 3000 mB. Мир и действия штуковины не откатываются. Подтверждение действует две минуты.","log cart confirm-recovery "+ID+" 100");
                else if(stage==3)mc.setScreen(panel("Journal"));
                else if(stage==4){
                    var screen=new com.roften.avilixlogger.client.gui.LoggerActionsScreen(root);
                    var actions=screen.getClass().getDeclaredField("actions");actions.setAccessible(true);actions.set(screen,com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS);
                    var allActions=screen.getClass().getDeclaredField("allActions");allActions.setAccessible(true);allActions.set(screen,com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS);
                    var groups=screen.getClass().getDeclaredField("groups");groups.setAccessible(true);var categories=new ArrayList<String>();categories.add("Все");categories.addAll(com.roften.avilixlogger.core.LoggerActionCatalog.ACTIONS.stream().map(com.roften.avilixlogger.core.LoggerActionCatalog.Action::group).distinct().toList());groups.set(screen,List.copyOf(categories));
                    var selected=screen.getClass().getDeclaredField("selected");selected.setAccessible(true);selected.setInt(screen,11);
                    mc.setScreen(screen);
                }
                else if(stage==5){
                    var screen=(com.roften.avilixlogger.client.gui.LoggerActionsScreen)mc.screen;
                    var request=screen.getClass().getDeclaredField("lastRequest");request.setAccessible(true);request.setLong(screen,1);
                    com.roften.avilixlogger.client.gui.LoggerActionsScreen.accept(new com.roften.avilixlogger.net.S2CLoggerActionResultPayload(1,"Подтвердить откат выбранной конструкции","SUGGEST_COMMAND","/log cart confirm "+ID+" 100"));
                    var results=screen.getClass().getDeclaredField("results");results.setAccessible(true);results.setBoolean(screen,true);
                    var refresh=screen.getClass().getDeclaredMethod("refresh");refresh.setAccessible(true);refresh.invoke(screen);
                }
                else if(stage==6){
                    var screen=(com.roften.avilixlogger.client.gui.LoggerActionsScreen)mc.screen;
                    check(screen.mouseClicked(15,65,0),"result link did not open a GUI form");
                    var values=screen.getClass().getDeclaredField("values");values.setAccessible(true);check(values.get(screen).equals(List.of(ID,"100")),"confirmation link lost ID/entry");
                    var guide=Class.forName("com.roften.avilixlogger.client.gui.LoggerGuideScreen").getDeclaredConstructor(Screen.class);guide.setAccessible(true);mc.setScreen((Screen)guide.newInstance(root));
                }
                else if(stage==7)mc.setScreen(new com.roften.avilixlogger.client.gui.LogViewerScreen());
                else if(stage==8)mc.setScreen(new com.roften.avilixlogger.client.gui.CaptureTypesScreen(mc.screen));
                if(stage<FILES.size())layout(mc.screen,mc);
                if(stage==8){
                    var capture=(com.roften.avilixlogger.client.gui.CaptureTypesScreen)mc.screen;
                    capture.apply(new com.roften.avilixlogger.net.S2CCaptureTypesPayload(1<<10,7,true,true,"Подшипники отключены без перезагрузки"));
                }
            }else{
                for(var name:FILES)check(new java.io.File(new java.io.File(mc.gameDirectory,"screenshots"),name).isFile(),"missing screenshot "+name);
                System.out.println("CART_GUI_CLIENT_OK: actual history/actions/confirmation/results, shared action forms, help and journal rendered; 320x240, 640x360, 960x540 widget bounds and overlap checks");finished=true;mc.stop();
            }
        }catch(Throwable error){error.printStackTrace();System.out.println("CART_GUI_CLIENT_FAILED");finished=true;mc.stop();}
    }
    private static Screen panel(String name)throws Exception{var c=Class.forName(CartHistoryScreen.class.getName()+"$"+name).getDeclaredConstructor(CartHistoryScreen.class);c.setAccessible(true);return (Screen)c.newInstance(root);}
    private static void layout(Screen screen,Minecraft mc){
        for(int[] size:new int[][]{{320,240},{640,360},{960,540}}){
            screen.resize(mc,size[0],size[1]);var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(widget->widget.visible).toList();
            for(int i=0;i<widgets.size();i++){var a=widgets.get(i);check(a.getX()>=0&&a.getY()>=0&&a.getX()+a.getWidth()<=size[0]&&a.getY()+a.getHeight()<=size[1],"widget outside screen: "+a.getMessage().getString());
                for(int j=i+1;j<widgets.size();j++){var b=widgets.get(j);check(a.getX()+a.getWidth()<=b.getX()||b.getX()+b.getWidth()<=a.getX()||a.getY()+a.getHeight()<=b.getY()||b.getY()+b.getHeight()<=a.getY(),"widgets overlap: "+a.getMessage().getString()+" / "+b.getMessage().getString());}}
        }
        screen.resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
