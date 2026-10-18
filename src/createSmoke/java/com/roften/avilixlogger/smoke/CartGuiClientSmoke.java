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
    private static int ticks,stage,readyTicks;
    private static boolean finished;
    private static final String ID="51000000-0000-0000-0000-000000000001";
    private static final List<String> FILES=List.of("cart-gui-history.png","cart-gui-actions.png","cart-gui-confirm.png","cart-gui-results.png");
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        if(!Boolean.getBoolean("avilixlogger.guiSmoke")||finished)return;
        var mc=Minecraft.getInstance();
        try{
            if(root==null){
                if(mc.screen==null||mc.getOverlay()!=null||++readyTicks<40)return;
                root=new CartHistoryScreen(mc.screen,"");mc.setScreen(root);
                var rows=new ArrayList<S2CCartHistoryPayload.Row>();for(int i=0;i<12;i++)rows.add(new S2CCartHistoryPayload.Row(ID,100-i,"18.10.2026 23:50:00","Штуковина: упаковка","MaxKuz","minecraft:overworld · -1775 38 -2726","Полный снимок: 125 блоков. Хранилища предметов и жидкости сохранены.",true,true));
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,0,"",88,true,"Все операции доступны через GUI","",rows));
                var selected=CartHistoryScreen.class.getDeclaredField("selected");selected.setAccessible(true);selected.setInt(root,0);
                root.apply(new S2CCartHistoryPayload(S2CCartHistoryPayload.DONE,0,"",0,false,"Штуковина выдана в свободный слот. entry=100. Сохранены блоки, компоненты предметов и жидкости.","",List.of()));
                layout(root,mc);ticks=0;return;
            }
            if(++ticks<15)return;ticks=0;
            if(stage<FILES.size()){
                Screenshot.grab(mc.gameDirectory,FILES.get(stage),mc.getMainRenderTarget(),message->System.out.println("CART_GUI_SCREENSHOT: "+message.getString()));
                stage++;
                if(stage==1)mc.setScreen(panel("Actions"));
                else if(stage==2)root.confirmation("Вернуть саму штуковину в предмет: "+ID+". entry=100; блоков: 125. Основа: снимок entry=80, применено изменений состояния: 12. Хранилищ предметов: 4, предметов: 128; баков: 2, жидкости: 3000 mB. Мир и действия штуковины не откатываются. Подтверждение действует две минуты.","log cart confirm-recovery "+ID+" 100");
                else if(stage==3)mc.setScreen(panel("Journal"));
                if(stage<FILES.size())layout(mc.screen,mc);
            }else{
                for(var name:FILES)check(new java.io.File(new java.io.File(mc.gameDirectory,"screenshots"),name).isFile(),"missing screenshot "+name);
                System.out.println("CART_GUI_CLIENT_OK: actual history/actions/confirmation/results rendered; 320x240, 640x360, 960x540 widget bounds and overlap checks");finished=true;mc.stop();
            }
        }catch(Throwable error){error.printStackTrace();System.out.println("CART_GUI_CLIENT_FAILED");finished=true;mc.stop();}
    }
    private static Screen panel(String name)throws Exception{var c=Class.forName(CartHistoryScreen.class.getName()+"$"+name).getDeclaredConstructor(CartHistoryScreen.class);c.setAccessible(true);return (Screen)c.newInstance(root);}
    private static void layout(Screen screen,Minecraft mc){
        for(int[] size:new int[][]{{320,240},{640,360},{960,540}}){
            screen.resize(mc,size[0],size[1]);var widgets=screen.children().stream().filter(AbstractWidget.class::isInstance).map(AbstractWidget.class::cast).toList();
            for(int i=0;i<widgets.size();i++){var a=widgets.get(i);check(a.getX()>=0&&a.getY()>=0&&a.getX()+a.getWidth()<=size[0]&&a.getY()+a.getHeight()<=size[1],"widget outside screen: "+a.getMessage().getString());
                for(int j=i+1;j<widgets.size();j++){var b=widgets.get(j);check(a.getX()+a.getWidth()<=b.getX()||b.getX()+b.getWidth()<=a.getX()||a.getY()+a.getHeight()<=b.getY()||b.getY()+b.getHeight()<=a.getY(),"widgets overlap: "+a.getMessage().getString()+" / "+b.getMessage().getString());}}
        }
        screen.resize(mc,mc.getWindow().getGuiScaledWidth(),mc.getWindow().getGuiScaledHeight());
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
