package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.client.gui.*;
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
    private static int ticks,stage,readyTicks;
    private static boolean finished;
    private static final String ID="51000000-0000-0000-0000-000000000001";
    private static final List<String> FILES=List.of("cart-gui-history.png","cart-gui-actions.png","cart-gui-confirm.png","cart-gui-results.png","cart-gui-main.png","cart-gui-functions.png");
    @SubscribeEvent public static void backdrop(net.neoforged.neoforge.client.event.ScreenEvent.Render.Pre event){
        // A real world redraws behind the transparent overlay every frame. At the title screen,
        // provide an equivalent fresh backdrop so old menu/widget pixels cannot contaminate QA.
        if(Boolean.getBoolean("avilixlogger.guiSmoke")&&root!=null&&event.getScreen() instanceof LogViewerScreen)
            event.getGuiGraphics().fill(0,0,event.getScreen().width,event.getScreen().height,0xFF16191D);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("avilixlogger.guiSmoke")||finished)return;
        var mc=Minecraft.getInstance();
        try{
            if(root==null){
                if(!(mc.screen instanceof net.minecraft.client.gui.screens.TitleScreen)||mc.getOverlay()!=null||++readyTicks<40)return;
                root=new CartHistoryScreen(mc.screen,"");mc.setScreen(root);
                var rows=new ArrayList<S2CCartHistoryPayload.Row>();
                var types=new com.roften.avilixlogger.core.ActionType[]{com.roften.avilixlogger.core.ActionType.CART_PACK,com.roften.avilixlogger.core.ActionType.CART_PLACE,com.roften.avilixlogger.core.ActionType.CART_ROLLBACK};
                for(int i=0;i<12;i++){var type=types[i%types.length];rows.add(new S2CCartHistoryPayload.Row(ID,100-i,"18.10.2026 23:50:00",com.roften.avilixlogger.core.LogText.actionLabel(type),"MaxKuz","minecraft:overworld · -1775 38 -2726","Владелец: Owner\nID: "+ID+"\nentry="+(100-i)+"\nСохранены блоки, механизмы, ресурсы и жидкости.",true,true,"Owner",i%3==0?"конструкция упакована в предмет":i%3==1?"предмет установлен в мир":"сохранённое состояние выдано предметом",com.roften.avilixlogger.core.LogText.actionColor(type).getColor()));}
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,0,"",88,true,"Все операции доступны через GUI","",rows));
                var select=LogViewerScreen.class.getDeclaredMethod("selectEntry",long.class);select.setAccessible(true);select.invoke(root,100L);
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.DONE,0,"",0,false,"Штуковина выдана в свободный слот. entry=100. Сохранены блоки, компоненты предметов и жидкости.","",List.of()));
                var pending=LogViewerScreen.class.getDeclaredField("pendingCopyJson");pending.setAccessible(true);
                mc.keyboardHandler.setClipboard("GUI smoke sentinel");pending.setBoolean(root,true);
                root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":99}"))));
                check(mc.keyboardHandler.getClipboard().equals("GUI smoke sentinel"),"neighbour JSON copied as selected entry");
                pending.setBoolean(root,true);root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal("{\"id\":100}"))));
                check(mc.keyboardHandler.getClipboard().equals("{\"id\":100}"),"selected JSON not copied");
                root.acceptDetails(new com.roften.avilixlogger.net.S2CLogDetailsPayload(100,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.DETAILS,List.of(net.minecraft.network.chat.Component.literal("Очень длинное описание сохранённого состояния. ".repeat(300)))));
                root.mouseScrolled(root.width-20,root.height-40,0,-1);
                var ds=LogViewerScreen.class.getDeclaredField("detailsScroll");ds.setAccessible(true);check(ds.getInt(root)>0,"wrapped details were rendered but could not be scrolled");
                String largeJson="{\"id\":42,\"beAfter\":\""+"сохранённые-данные-".repeat(4000)+"\"}";
                var large=new com.roften.avilixlogger.net.S2CLogDetailsPayload(42,com.roften.avilixlogger.net.C2SRequestDetailsPayload.Mode.JSON,List.of(net.minecraft.network.chat.Component.literal(largeJson)));
                var buf=new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(),net.minecraft.core.RegistryAccess.EMPTY);
                try{com.roften.avilixlogger.net.S2CLogDetailsPayload.STREAM_CODEC.encode(buf,large);check(com.roften.avilixlogger.net.S2CLogDetailsPayload.STREAM_CODEC.decode(buf).lines().getFirst().getString().equals(largeJson),"large JSON export truncated");}finally{buf.release();}
                check(root instanceof LogViewerScreen,"cart renderer is not the main logger renderer");
                check(root.children().stream().filter(net.minecraft.client.gui.components.Button.class::isInstance).allMatch(w->w.getClass().getName().contains("LogViewerScreen$ScaledTextButton")),"cart uses separate button styling");
                var functions=new LoggerFunctionsScreen(root);mc.setScreen(functions);layout(functions,mc);mc.setScreen(root);
                select.invoke(root,100L);layout(root,mc);ticks=0;return;
            }
            if(++ticks<15)return;ticks=0;
            if(stage<FILES.size()){
                Screenshot.grab(mc.gameDirectory,FILES.get(stage),mc.getMainRenderTarget(),message->System.out.println("CART_GUI_SCREENSHOT: "+message.getString()));
                stage++;
                if(stage==1)mc.setScreen(panel("Actions"));
                else if(stage==2)root.confirmation("Вернуть саму штуковину в предмет: "+ID+". entry=100; блоков: 125. Основа: снимок entry=80, применено изменений состояния: 12. Хранилищ предметов: 4, предметов: 128; баков: 2, жидкости: 3000 mB. Мир и действия штуковины не откатываются. Подтверждение действует две минуты.","log cart confirm-recovery "+ID+" 100");
                else if(stage==3)mc.setScreen(panel("Journal"));
                else if(stage==4){var main=new LogViewerScreen();mc.setScreen(main);var f=LogViewerScreen.class.getDeclaredField("rows");f.setAccessible(true);@SuppressWarnings("unchecked") var displayed=(List<com.roften.avilixlogger.net.LogRow>)f.get(root);main.apply(new com.roften.avilixlogger.net.S2CLogPagePayload("Логгер · сравнение оформления",1,false,true,List.copyOf(displayed)));}
                else if(stage==5){var functions=new LoggerFunctionsScreen(root);var f=LoggerFunctionsScreen.class.getDeclaredField("operation");f.setAccessible(true);f.set(functions,com.roften.avilixlogger.net.LoggerGuiOperation.LOOKUP);mc.setScreen(functions);}
                if(stage<FILES.size())layout(mc.screen,mc);
            }else{
                for(var name:FILES)check(new java.io.File(new java.io.File(mc.gameDirectory,"screenshots"),name).isFile(),"missing screenshot "+name);
                System.out.println("CART_GUI_CLIENT_OK: shared main logger renderer/widgets, forms, JSON exactness+large wire, clean-frame main/history/actions/confirmation/results/search-form rendered; 320x240, 640x360, 960x540 widget bounds and overlap checks");finished=true;mc.stop();
            }
        }catch(Throwable error){error.printStackTrace();System.out.println("CART_GUI_CLIENT_FAILED");finished=true;mc.stop();}
    }
    private static Screen panel(String name)throws Exception{var c=Class.forName(CartHistoryScreen.class.getName()+"$"+name).getDeclaredConstructor(CartHistoryScreen.class);c.setAccessible(true);return (Screen)c.newInstance(root);}
    private static void layout(Screen screen,Minecraft mc){
        for(int[] size:new int[][]{{320,240},{640,360},{960,540}}){
            screen.resize(mc,size[0],size[1]);var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).filter(w->w.visible).toList();
            for(int i=0;i<widgets.size();i++){var a=widgets.get(i);check(a.getX()>=0&&a.getY()>=0&&a.getX()+a.getWidth()<=size[0]&&a.getY()+a.getHeight()<=size[1],"widget outside screen: "+a.getMessage().getString());
                for(int j=i+1;j<widgets.size();j++){var b=widgets.get(j);check(a.getX()+a.getWidth()<=b.getX()||b.getX()+b.getWidth()<=a.getX()||a.getY()+a.getHeight()<=b.getY()||b.getY()+b.getHeight()<=a.getY(),"widgets overlap: "+a.getMessage().getString()+" / "+b.getMessage().getString());}}
        }
        screen.resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
