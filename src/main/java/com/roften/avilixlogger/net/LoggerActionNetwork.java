package com.roften.avilixlogger.net;

import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.core.LoggerActionCatalog;
import net.minecraft.commands.CommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Runs existing Brigadier nodes with the real player's unchanged permissions. */
final class LoggerActionNetwork {
    private LoggerActionNetwork(){}
    static void handle(C2SLoggerActionPayload payload,IPayloadContext context){context.enqueueWork(()->{
        if(!(context.player() instanceof ServerPlayer player)||!LoggerNetwork.isClientPresent(player))return;
        var source=player.createCommandSourceStack().withSource(new CommandSource(){
            public void sendSystemMessage(Component text){
                var result=S2CLoggerActionResultPayload.from(payload.request(),text);
                player.getServer().execute(()->{
                    if(player.getServer().getPlayerList().getPlayer(player.getUUID())==player&&AuthCoreBridge.isAuthorized(player.getServer()))
                        PacketDistributor.sendToPlayer(player,result);
                });
            }
            public boolean acceptsSuccess(){return true;}
            public boolean acceptsFailure(){return true;}
            public boolean shouldInformAdmins(){return true;}
        });
        if(!com.roften.avilixlogger.core.PermissionUtil.isAdministrator(player))return;
        try{
            String command=LoggerActionCatalog.find(payload.action()).command(payload.values());
            player.getServer().getCommands().performPrefixedCommand(source,command);
        }catch(RuntimeException error){source.sendFailure(Component.literal(error.getMessage()==null?"Не удалось выполнить действие":error.getMessage()));}
    });}
}
