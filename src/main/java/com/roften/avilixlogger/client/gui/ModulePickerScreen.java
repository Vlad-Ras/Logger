package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.ModLogFilters;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Dedicated module journals reuse the existing journal and its details/actions panels. */
final class ModulePickerScreen extends Screen {
    private final LogViewerScreen parent;
    private int scroll;
    ModulePickerScreen(LogViewerScreen parent){super(Component.literal("Журналы модов"));this.parent=parent;}
    private double scale(){return LogViewerScreen.sharedControlScale(width,height);}
    protected void init(){
        int w=Math.min(420,width-20),x=(width-w)/2,visible=Math.max(1,(height-80)/24);
        scroll=Math.min(scroll,Math.max(0,ModLogFilters.MODULES.size()-visible));
        for(int i=0;i<visible&&scroll+i<ModLogFilters.MODULES.size();i++){
            var module=ModLogFilters.MODULES.get(scroll+i);
            addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,34+i*24,w,20,Component.literal(module.title()),
                b->LogViewerClientHooks.openModule(parent,module.preset()),this::scale));
        }
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(x,height-30,w,20,Component.literal("Назад"),b->onClose(),this::scale));
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){
        scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);clearWidgets();init();return true;
    }
    public void render(GuiGraphics g,int mx,int my,float dt){
        LoggerGuiWidgets.beginFrame(g,width,height);g.drawCenteredString(font,title,width/2,12,0xFFD700);
        g.drawCenteredString(font,"Прокрутите список · "+(scroll+1)+" / "+ModLogFilters.MODULES.size(),width/2,height-45,0xAAAAAA);
        for(var widget:renderables)widget.render(g,mx,my,dt);
    }
    public void renderBackground(GuiGraphics g,int x,int y,float dt){}
    public void onClose(){minecraft.setScreen(parent);}
    public boolean isPauseScreen(){return false;}
}
