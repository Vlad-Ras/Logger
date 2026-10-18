package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.net.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import io.netty.buffer.Unpooled;
import java.util.*;

/** Optional-network codec and production GUI query builder in the real server runtime. */
@net.neoforged.fml.common.EventBusSubscriber(modid="avilixlogger")
public final class GuiSearchSmoke {
    @net.neoforged.bus.api.SubscribeEvent public static void start(net.neoforged.neoforge.event.server.ServerStartedEvent e){
        if(!Boolean.getBoolean("avilixlogger.guiSearchSmoke"))return;
        try{run(e.getServer().overworld());}catch(Throwable failure){failure.printStackTrace();System.out.println("GUI_SEARCH_NATIVE_FAILED");}finally{e.getServer().halt(false);}
    }
    public static void run(ServerLevel world)throws Exception{
        int tested=0;
        var actor=new net.minecraft.server.level.ServerPlayer(world.getServer(),world,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"gui_fixture"),net.minecraft.server.level.ClientInformation.createDefault());
        var build=LoggerNetwork.class.getDeclaredMethod("buildBaseGuiQuery",net.minecraft.server.level.ServerPlayer.class,GuiFilters.class,int.class);build.setAccessible(true);
        for(int size:List.of(10,60,400))for(var nav:C2SRequestPagePayload.Nav.values()){
            var filters=new GuiFilters(1,3,5,-1,0,"Alice","Express","","minecraft:stone",1760000000000L,1790000000000L,"needle");
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),world.registryAccess());
            try{
                var payload=new C2SRequestPagePayload(nav,false,filters,size);C2SRequestPagePayload.STREAM_CODEC.encode(buffer,payload);
                check(payload.equals(C2SRequestPagePayload.STREAM_CODEC.decode(buffer))&&buffer.readableBytes()==0,"page wire consumed wrong fields");
                var query=(LogQuery)build.invoke(null,actor,filters,size);
                check(query.sinceTs==filters.absoluteSinceTs()&&query.untilTs<=filters.absoluteUntilTs()&&"*".equals(query.dim),"absolute bounds/world query lost");
                check(query.limit==size&&"needle".equals(query.textFilter)&&"Express".equals(query.trainNameFilter)&&"train".equals(query.extraTextFilter)&&"Alice".equals(query.actorName)&&"minecraft:stone".equals(query.blockIdFilter),"production builder omitted GUI predicate");
            }finally{buffer.release();}tested++;
        }
        for(String zone:List.of("UTC","Europe/Moscow","Europe/Berlin")){
            var buffer=new RegistryFriendlyByteBuf(Unpooled.buffer(),world.registryAccess());try{var payload=new S2COpenGuiPayload(zone);S2COpenGuiPayload.STREAM_CODEC.encode(buffer,payload);check(payload.equals(S2COpenGuiPayload.STREAM_CODEC.decode(buffer))&&buffer.readableBytes()==0,"journal zone lost on GUI open");}finally{buffer.release();}tested++;
        }
        var broken=new RegistryFriendlyByteBuf(Unpooled.buffer(),world.registryAccess());try{broken.writeByte(0);broken.writeBoolean(false);boolean rejected=false;try{C2SRequestPagePayload.STREAM_CODEC.decode(broken);}catch(RuntimeException expected){rejected=true;}check(rejected,"malformed filters silently became an unfiltered query");}finally{broken.release();}tested++;
        System.out.println("GUI_SEARCH_NATIVE_OK: "+tested+" cases; production builder, page sizes/nav, absolute bounds, server zone, strict codec");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
