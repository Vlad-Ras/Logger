package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.core.LoggerUserGuide;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LoggerGuideScreen extends Screen {
    private final Screen parent;
    private final WrappedLogText layout=new WrappedLogText();
    private int topic,scroll;
    LoggerGuideScreen(Screen parent){super(Component.literal("Справка логгера"));this.parent=parent;}
    protected void init(){
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(10,30,35,20,Component.literal("←"),b->change(-1),()->LogViewerScreen.sharedControlScale(width,height)));
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(width-45,30,35,20,Component.literal("→"),b->change(1),()->LogViewerScreen.sharedControlScale(width,height)));
        addRenderableWidget(new LoggerGuiWidgets.ScaledTextButton(10,height-28,90,20,Component.literal("Назад"),b->onClose(),()->LogViewerScreen.sharedControlScale(width,height)));
    }
    private void change(int delta){topic=Math.floorMod(topic+delta,LoggerUserGuide.TOPICS.size());scroll=0;layout.clear();}
    public void render(GuiGraphics g,int x,int y,float dt){
        g.drawCenteredString(font,title,width/2,10,0xFFAA00);var current=LoggerUserGuide.TOPICS.get(topic);
        g.drawCenteredString(font,(topic+1)+" / "+LoggerUserGuide.TOPICS.size()+" · "+current.title(),width/2,36,0xFFFFFF);
        var lines=layout.get(font,current.text(),width-28);int visible=Math.max(1,(height-100)/12);scroll=Math.min(scroll,Math.max(0,lines.size()-visible));
        g.fill(8,58,width-8,height-36,0x44000000);g.enableScissor(12,62,width-12,height-38);
        for(int i=scroll;i<Math.min(lines.size(),scroll+visible);i++)g.drawString(font,lines.get(i),14,62+(i-scroll)*12,0xFFFFFF);g.disableScissor();
        for(var widget:renderables)widget.render(g,x,y,dt);
    }
    public boolean mouseScrolled(double x,double y,double dx,double dy){scroll=Math.max(0,scroll-(int)Math.signum(dy)*3);return true;}
    public void renderBackground(GuiGraphics g,int x,int y,float dt){}
    public boolean isPauseScreen(){return false;}
    public void onClose(){minecraft.setScreen(parent);}
}
