package com.roften.avilixlogger.net;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.auth.AuthCoreBridge;
import com.roften.avilixlogger.compat.create.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.*;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Database queries, NBT parsing and formatting run on the bounded history worker. */
public final class CartHistoryNetwork {
    private static final Map<UUID,Long> LAST_REQUEST=new HashMap<>();
    private static final Set<UUID> VIEWERS=new HashSet<>();
    private static final Map<UUID,String> LAST_RESULT=new HashMap<>();
    private static long sequence;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss");
    private CartHistoryNetwork(){}
    public static boolean viewing(CommandSourceStack source){return source.getPlayer()!=null&&allowed(source.getPlayer())&&VIEWERS.contains(source.getPlayer().getUUID())&&LoggerNetwork.isClientPresent(source.getPlayer());}
    public static boolean detailsAllowed(ServerPlayer p){return VIEWERS.contains(p.getUUID())&&allowed(p);}
    private static boolean allowed(ServerPlayer p){return CreateSupport.available()&&AuthCoreBridge.isAuthorized(p.getServer())&&PermissionUtil.has(p.createCommandSourceStack(),"avilixlogger.command.lookup",2);}
    public static boolean open(CommandSourceStack source,String id){
        var p=source.getPlayer();if(p==null||!allowed(p)||!LoggerNetwork.isClientPresent(p))return false;VIEWERS.add(p.getUUID());
        PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.OPEN,0,id,0,restore(p),"","",List.of()));return true;
    }
    public static void handleAction(C2SCartActionPayload payload,IPayloadContext ctx){ctx.enqueueWork(()->{
        if(!(ctx.player() instanceof ServerPlayer p))return;var source=p.createCommandSourceStack();
        if(payload.action()==C2SCartActionPayload.Action.CLOSE){
            VIEWERS.remove(p.getUUID());LAST_REQUEST.remove(p.getUUID());
            if(com.roften.avilixlogger.core.CreateSupport.available()){CartItemRecovery.cancel(source);CartRollbackCoordinator.cancelPreview(source);}return;
        }
        if(!allowed(p)){error(source,"Нет доступа к истории или Create недоступен");return;}
        VIEWERS.add(p.getUUID());
        if(payload.action().restorePermission()&&!restore(p)){error(source,"Нет права восстановления avilixlogger.command.rollback");return;}
        try{switch(payload.action()){
            case HELD->{var id=CreateCartAudit.itemId(p.getMainHandItem());if(id==null)error(source,"В основной руке нет учтённой вагонеточной штуковины");else open(source,id.toString());}
            case RECOVER->{CartRollbackCoordinator.cancelPreview(source);CartItemRecovery.preview(source,UUID.fromString(payload.cartId()),payload.entry());}
            case ROLLBACK->{CartItemRecovery.cancel(source);CartRollbackCoordinator.preview(source,UUID.fromString(payload.cartId()),payload.entry());}
            case CONFIRM_RECOVERY->CartItemRecovery.confirm(source,UUID.fromString(payload.cartId()),payload.entry());
            case CONFIRM_ROLLBACK->CartRollbackCoordinator.confirm(source,UUID.fromString(payload.cartId()),payload.entry());
            case CANCEL_RECOVERY->{CartItemRecovery.cancel(source);if(CartItemRecovery.issuing(p.getUUID()))progress(source,"Выдача уже подтверждена: идёт фиксация результата. "+CartItemRecovery.status(p.getUUID()));else if(CartRollbackCoordinator.running(p.getUUID()))progress(source,"Предпросмотр восстановления отменён. "+CartRollbackCoordinator.status(p.getUUID()));else done(source,"Запрос или предпросмотр восстановления отменён.");}
            case CANCEL_ROLLBACK->{CartRollbackCoordinator.cancel(source);if(CartRollbackCoordinator.running(p.getUUID()))progress(source,"Запрос отмены обработан. "+CartRollbackCoordinator.status(p.getUUID()));else if(CartItemRecovery.issuing(p.getUUID()))progress(source,"Предпросмотр отката отменён. "+CartItemRecovery.status(p.getUUID()));else done(source,"Запрос или предпросмотр отката действий отменён.");}
            case STATUS->{String message=CartItemRecovery.status(p.getUUID())+"\n"+CartRollbackCoordinator.status(p.getUUID())+"\nПоследнее сообщение: "+LAST_RESULT.getOrDefault(p.getUUID(),"нет");if(CartItemRecovery.issuing(p.getUUID())||CartRollbackCoordinator.running(p.getUUID()))progress(source,message);else notice(source,message);}
            case CLOSE->{}
        }}catch(RuntimeException e){error(source,e.getMessage());}
    });}
    public static void handle(C2SCartHistoryPayload payload,IPayloadContext ctx){ctx.enqueueWork(()->{
        if(!(ctx.player() instanceof ServerPlayer p))return;
        if(!allowed(p)){pageError(p,payload.request(),"Нет доступа к истории или Create недоступен");return;}
        VIEWERS.add(p.getUUID());LogQuery q;
        try{q=CartHistoryQuery.build(payload);}catch(IllegalArgumentException e){pageError(p,payload.request(),e.getMessage());return;}
        if(LAST_REQUEST.containsKey(p.getUUID())){pageError(p,payload.request(),"Предыдущая страница ещё загружается");return;}
        long token=++sequence;LAST_REQUEST.put(p.getUUID(),token);
        var storage=LoggerRuntime.storage(p.serverLevel());var server=p.getServer();boolean canRestore=restore(p);var zone=ZoneId.systemDefault();
        try{boolean queued=CartRollbackCoordinator.query(p.createCommandSourceStack(),()->{try{
            var found=storage.queryReverse(q);var rows=new ArrayList<S2CCartHistoryPayload.Row>();
            for(var e:found.subList(0,Math.min(12,found.size()))){var cart=CartAuditContext.id(e.source);if(cart==null||q.cartId!=null&&!q.cartId.equals(cart))throw new IllegalStateException("Чужая строка в истории штуковины");
                var before=NbtSerde.fromSnbt(e.beBefore);var after=NbtSerde.fromSnbt(e.beAfter);boolean snapshot=CartRecoveryPlan.full(after)||CartRecoveryPlan.full(before);
                String detail="entry="+e.id+"\nID: "+cart+"\n"+(snapshot?"Есть полный снимок состояния.":"Состояние запрашивается из предшествующего полного снимка и записанных изменений.")+"\n"+Objects.toString(e.extra,"");
                if(before!=null)detail+="\nДо: "+before.getString("Form");if(after!=null)detail+="\nПосле: "+after.getString("Form");
                rows.add(new S2CCartHistoryPayload.Row(cart.toString(),e.id,TIME.format(Instant.ofEpochMilli(e.ts).atZone(zone)),LogText.actionLabel(e.type),Objects.toString(e.actorName,"SYSTEM"),e.dim+" · "+e.x+" "+e.y+" "+e.z,clip(detail,4000),CartRollbackPlan.checkpoint(e.type),snapshot));
            }
            long next=found.size()>12?rows.getLast().entry():0;
            server.execute(()->{if(!Objects.equals(LAST_REQUEST.get(p.getUUID()),token))return;LAST_REQUEST.remove(p.getUUID());if(server.getPlayerList().getPlayer(p.getUUID())==p&&!allowed(p)){pageError(p,payload.request(),"Авторизация или права изменились");return;}if(server.getPlayerList().getPlayer(p.getUUID())==p)PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,payload.request(),payload.cartId(),next,canRestore,"Часовой пояс: "+zone+" · записей на странице: "+rows.size(),"",List.copyOf(rows)));});
        }catch(Exception e){server.execute(()->{if(!Objects.equals(LAST_REQUEST.get(p.getUUID()),token))return;LAST_REQUEST.remove(p.getUUID());pageError(p,payload.request(),e.getMessage());});}});
        if(!queued){LAST_REQUEST.remove(p.getUUID());pageError(p,payload.request(),"Очередь истории занята или Logger отключён");}
        }catch(RuntimeException e){LAST_REQUEST.remove(p.getUUID());pageError(p,payload.request(),e.getMessage());}
    });}
    private static void pageError(ServerPlayer p,long request,String message){PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.ERROR,request,"",0,false,clip(message,4000),"",List.of()));}
    private static boolean restore(ServerPlayer p){return PermissionUtil.has(p.createCommandSourceStack(),"avilixlogger.command.rollback",2);}
    public static void confirm(CommandSourceStack source,String message,String command){
        if(viewing(source))PacketDistributor.sendToPlayer(source.getPlayer(),new S2CCartHistoryPayload(S2CCartHistoryPayload.CONFIRM,0,"",0,true,clip(message,4000),command,List.of()));
        else{source.sendSystemMessage(Component.literal(message));source.sendSystemMessage(Component.literal("[Подтвердить]").withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/"+command))));}
    }
    public static void progress(CommandSourceStack source,String message){report(source,S2CCartHistoryPayload.PROGRESS,message);}
    public static void done(CommandSourceStack source,String message){report(source,S2CCartHistoryPayload.DONE,message);}
    public static void notice(CommandSourceStack source,String message){report(source,S2CCartHistoryPayload.NOTICE,message);}
    public static void error(CommandSourceStack source,String message){report(source,S2CCartHistoryPayload.ERROR,message);}
    private static void report(CommandSourceStack source,int kind,String message){
        if(!source.getServer().isSameThread()){String captured=message;source.getServer().execute(()->report(source,kind,captured));return;}
        message=Objects.toString(message,"Неизвестная ошибка истории");
        var p=source.getPlayer();if(p!=null&&kind!=S2CCartHistoryPayload.NOTICE&&!message.contains("Последнее сообщение:"))LAST_RESULT.put(p.getUUID(),clip(message,4000));
        if(viewing(source))PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(kind,0,"",0,false,clip(message,4000),"",List.of()));
        else if(kind==S2CCartHistoryPayload.ERROR)source.sendFailure(Component.literal(message));else source.sendSystemMessage(Component.literal(message));
    }
    private static String clip(String s,int max){return s==null?"":s.substring(0,Math.min(s.length(),max));}
    public static void logout(UUID id){LAST_REQUEST.remove(id);VIEWERS.remove(id);LAST_RESULT.remove(id);if(com.roften.avilixlogger.core.CreateSupport.available()){CartItemRecovery.logout(id);CartRollbackCoordinator.logout(id);com.roften.avilixlogger.compat.create.CreateTrainRepresentationCoordinator.logout(id);}}
    public static void stop(){LAST_REQUEST.clear();VIEWERS.clear();LAST_RESULT.clear();}
}
