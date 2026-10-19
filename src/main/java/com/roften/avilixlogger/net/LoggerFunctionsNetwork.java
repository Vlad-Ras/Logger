package com.roften.avilixlogger.net;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
public final class LoggerFunctionsNetwork {
    private static final java.util.Map<java.util.UUID,Long> LAST=new java.util.HashMap<>();
    private LoggerFunctionsNetwork(){}
    public static void clear(java.util.UUID id){LAST.remove(id);}
    public static void handle(C2SLoggerFunctionPayload payload,IPayloadContext ctx){ctx.enqueueWork(()->{
        if(!(ctx.player() instanceof ServerPlayer p)||!LoggerNetwork.isClientPresent(p)||!AuthCoreBridge.isAuthorized(p.getServer()))return;
        long now=System.currentTimeMillis(),last=LAST.getOrDefault(p.getUUID(),0L);if(now-last<300){send(p,payload.request(),"Подождите перед следующим запросом");return;}LAST.put(p.getUUID(),now);
        var capture=new CommandSource(){
            private int messages;
            public void sendSystemMessage(Component message){if(++messages>300)return;String text=message.getString();send(p,payload.request(),text.length()>8100?text.substring(0,8100)+"…":text);}
            public boolean acceptsSuccess(){return true;}public boolean acceptsFailure(){return true;}public boolean shouldInformAdmins(){return false;}
        };
        // withSource retains the real player, world, position and permissions. Never elevate the source.
        var source=p.createCommandSourceStack().withSource(capture);
        try{p.getServer().getCommands().performPrefixedCommand(source,payload.operation().command(payload.first(),payload.second()));}
        catch(RuntimeException e){send(p,payload.request(),"Ошибка: "+e.getMessage());}
    });}
    private static void send(ServerPlayer p,long request,String text){if(!p.getServer().isSameThread()){p.getServer().execute(()->send(p,request,text));return;}if(p.getServer().getPlayerList().getPlayer(p.getUUID())==p&&LoggerNetwork.isClientPresent(p))PacketDistributor.sendToPlayer(p,new S2CLoggerFunctionPayload(request,text));}
}
