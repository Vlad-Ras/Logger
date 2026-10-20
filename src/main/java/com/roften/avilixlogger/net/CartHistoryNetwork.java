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
/** All database queries, NBT parsing and row formatting run on the bounded history worker. */
public final class CartHistoryNetwork {
    private static final Map<UUID,Long> LAST_REQUEST=new HashMap<>();
    private static long sequence;
    private static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("dd.MM.uuuu HH:mm:ss");
    private CartHistoryNetwork(){}
    public static boolean open(CommandSourceStack source,String id){
        var p=source.getPlayer();if(p==null||!LoggerNetwork.isClientPresent(p))return false;
        PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.OPEN,0,id,0,restore(p),"","",List.of()));return true;
    }
    public static void handle(C2SCartHistoryPayload payload,IPayloadContext ctx){ctx.enqueueWork(()->{
        if(!(ctx.player() instanceof ServerPlayer p)||!com.roften.avilixlogger.core.CreateSupport.available()||!AuthCoreBridge.isAuthorized(p.getServer())
                ||!PermissionUtil.has(p.createCommandSourceStack(),"avilixlogger.command.lookup",2)){if(ctx.player() instanceof ServerPlayer denied)error(denied.createCommandSourceStack(),"Нет доступа к истории или Create недоступен");return;}
        UUID id;try{id=payload.cartId().isBlank()?null:UUID.fromString(payload.cartId());}catch(IllegalArgumentException e){error(p.createCommandSourceStack(),"Неверный ID штуковины");return;}
        if(payload.before()<0)return;
        if(LAST_REQUEST.containsKey(p.getUUID())){error(p.createCommandSourceStack(),"Предыдущая страница ещё загружается");return;}
        long token=++sequence;LAST_REQUEST.put(p.getUUID(),token);
        var q=new LogQuery();q.dim="*";q.cartId=id;q.sourcePrefix="create:cart:";q.sinceTs=0;q.untilTs=Long.MAX_VALUE;q.beforeId=payload.before();q.requireDetails=true;q.limit=13;
        var storage=LoggerRuntime.storage(p.serverLevel());var server=p.getServer();boolean canRestore=restore(p);var zone=ZoneId.systemDefault();
        try{boolean queued=CartRollbackCoordinator.query(p.createCommandSourceStack(),()->{try{
            var found=storage.queryReverse(q);var rows=new ArrayList<S2CCartHistoryPayload.Row>();
            for(var e:found.subList(0,Math.min(12,found.size()))){var cart=CartAuditContext.id(e.source);if(cart==null||id!=null&&!id.equals(cart))throw new IllegalStateException("Чужая строка в истории штуковины");
                var before=NbtSerde.fromSnbt(e.beBefore);var after=NbtSerde.fromSnbt(e.beAfter);boolean snapshot=CartRecoveryPlan.full(after)||CartRecoveryPlan.full(before);
                String detail="entry="+e.id+"\nID: "+cart+"\n"+(snapshot?"Есть полный снимок: можно запросить восстановление в предмет.":"Полного снимка в этой строке нет.")+"\n"+Objects.toString(e.extra,"");
                if(before!=null)detail+="\nДо: "+before.getString("Form");if(after!=null)detail+="\nПосле: "+after.getString("Form");
                rows.add(new S2CCartHistoryPayload.Row(cart.toString(),e.id,TIME.format(Instant.ofEpochMilli(e.ts).atZone(zone)),LogText.actionLabel(e.type),Objects.toString(e.actorName,"SYSTEM"),e.dim+" · "+e.x+" "+e.y+" "+e.z,clip(detail,4000),CartRollbackPlan.checkpoint(e.type),snapshot));
            }
            long next=found.size()>12?rows.getLast().entry():0;
            server.execute(()->{if(!Objects.equals(LAST_REQUEST.get(p.getUUID()),token))return;LAST_REQUEST.remove(p.getUUID());if(server.getPlayerList().getPlayer(p.getUUID())==p&&com.roften.avilixlogger.LoggerConfig.isEnabled())PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.PAGE,payload.request(),payload.cartId(),next,canRestore,"Часовой пояс: "+zone+" · полный снимок и откат действий — разные операции","",List.copyOf(rows)));});
        }catch(Exception e){server.execute(()->{if(!Objects.equals(LAST_REQUEST.get(p.getUUID()),token))return;LAST_REQUEST.remove(p.getUUID());error(p.createCommandSourceStack(),e.getMessage());});}});
        if(!queued){LAST_REQUEST.remove(p.getUUID());error(p.createCommandSourceStack(),"Очередь истории занята или Logger отключён");}
        }catch(RuntimeException e){LAST_REQUEST.remove(p.getUUID());error(p.createCommandSourceStack(),e.getMessage());}
    });}
    private static boolean restore(ServerPlayer p){return PermissionUtil.has(p.createCommandSourceStack(),"avilixlogger.command.rollback",2);}
    public static void confirm(CommandSourceStack source,String message,String command){
        source.sendSystemMessage(Component.literal(message));
        source.sendSystemMessage(Component.literal("[Подтвердить]").withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/"+command))));
        var p=source.getPlayer();if(p!=null&&LoggerNetwork.isClientPresent(p))PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.CONFIRM,0,"",0,true,message,command,List.of()));
    }
    public static void error(CommandSourceStack source,String message){
        message=Objects.toString(message,"Неизвестная ошибка истории");
        source.sendFailure(Component.literal(message));var p=source.getPlayer();if(p!=null&&LoggerNetwork.isClientPresent(p))PacketDistributor.sendToPlayer(p,new S2CCartHistoryPayload(S2CCartHistoryPayload.ERROR,0,"",0,false,clip(message,4000),"",List.of()));
    }
    private static String clip(String s,int max){return s==null?"":s.substring(0,Math.min(s.length(),max));}
    public static void logout(UUID id){LAST_REQUEST.remove(id);if(com.roften.avilixlogger.core.CreateSupport.available()){CartItemRecovery.logout(id);CreateTrainRecovery.logout(id);}}
    public static void stop(){LAST_REQUEST.clear();}
}
