package com.roften.avilixlogger.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.neoforged.fml.ModList;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class CartCommands {
    private CartCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return literal("cart").requires(s->com.roften.avilixlogger.core.CreateSupport.available() && PermissionUtil.has(s,"avilixlogger.command.lookup",2))
            .executes(c->{if(com.roften.avilixlogger.net.CartHistoryNetwork.open(c.getSource(),""))return 1;c.getSource().sendSystemMessage(Component.literal("/log cart — GUI | find <игрок> | history <ID> | chat <ID> [before] | recover <ID> latest|<entry> | undo-transfer/undo-disassembly/undo-coupling <ID> <entry> | rollback <ID> <entry> | confirm | cancel"));return 1;})
            .then(literal("find").then(argument("player",StringArgumentType.word()).executes(c->{find(c.getSource(),StringArgumentType.getString(c,"player"));return 1;})))
            .then(literal("chat").then(argument("id",StringArgumentType.word()).executes(c->{chatHistory(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;})
                .then(argument("before",LongArgumentType.longArg(1)).executes(c->{chatHistory(c.getSource(),StringArgumentType.getString(c,"id"),LongArgumentType.getLong(c,"before"));return 1;}))))
            .then(literal("recover").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).then(argument("id",StringArgumentType.word())
                .executes(c->{recover(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;})
                .then(literal("latest").executes(c->{recover(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;}))
                .then(argument("entry",LongArgumentType.longArg(1)).executes(c->{recover(c.getSource(),StringArgumentType.getString(c,"id"),LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("confirm-recovery").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CartItemRecovery.confirm(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("cancel-recovery").executes(c->{CartItemRecovery.cancel(c.getSource());return 1;}))
            .then(literal("history").then(argument("id",StringArgumentType.word())
                .executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;})
                .then(argument("before",LongArgumentType.longArg(1)).executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),LongArgumentType.getLong(c,"before"));return 1;}))))
            .then(literal("held").executes(c->{var p=c.getSource().getPlayerOrException();UUID id=CreateCartAudit.itemId(p.getMainHandItem());if(id==null)c.getSource().sendFailure(Component.literal("В основной руке нет учтённой вагонеточной конструкции"));else history(c.getSource(),id.toString(),0);return 1;}))
            .then(literal("rollback").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CartRollbackCoordinator.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("recover-train").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateTrainRecovery.preview(c.getSource(),id,0);return 1;})
                    .then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateTrainRecovery.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("confirm-train").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateTrainRecovery.confirm(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("undo-transfer").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateTransferRollbackCoordinator.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("confirm-transfer").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CreateTransferRollbackCoordinator.confirm(c.getSource());return 1;}))
            .then(literal("undo-disassembly").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateStructuralRollbackCoordinator.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("undo-coupling").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CreateCouplingRollbackCoordinator.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("confirm-coupling").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CreateCouplingRollbackCoordinator.confirm(c.getSource());return 1;}))
            .then(literal("confirm-structure").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CreateStructuralRollbackCoordinator.confirm(c.getSource());return 1;}))
            .then(literal("confirm").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CartRollbackCoordinator.confirm(c.getSource());return 1;})
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CartRollbackCoordinator.confirm(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("cancel").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CreateTrainRecovery.cancel(c.getSource());CartRollbackCoordinator.cancel(c.getSource());CreateTransferRollbackCoordinator.cancel(c.getSource());CreateStructuralRollbackCoordinator.cancel(c.getSource());CreateCouplingRollbackCoordinator.cancel(c.getSource());return 1;}));
    }
    private static void recover(CommandSourceStack source,String value,long entry){UUID id=parse(source,value);if(id!=null)CartItemRecovery.preview(source,id,entry);}
    private static UUID parse(CommandSourceStack s,String value){try{return UUID.fromString(value);}catch(IllegalArgumentException e){s.sendFailure(Component.literal("Неверный ID конструкции"));return null;}}
    private static void find(CommandSourceStack source,String player){
        LogQuery q=new LogQuery();q.dim="*";q.actorName=player;q.limit=200;q.sinceTs=0;
        q.types=EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE);
        var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{try{
            var rows=storage.queryReverse(q);
            source.getServer().execute(()->{Set<UUID> seen=new HashSet<>();for(var row:rows){UUID id=CreateContraptionKind.id(row.source);if(id==null||!seen.add(id))continue;
                source.sendSystemMessage(Component.literal("").append(copyButton("[ID] ",id.toString())).append(Component.literal(id+" · "+LogText.actionLabel(row.type)+" · "+row.dim+" "+row.x+" "+row.y+" "+row.z).withStyle(st->st.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log cart history "+id)))));}
                if(seen.isEmpty())source.sendSystemMessage(Component.literal("Конструкции по действиям "+player+" не найдены"));else source.sendSystemMessage(Component.literal("По последним 200 событиям игрока. Нажмите ID для истории."));});
        }catch(Exception e){error(source,e);}});
    }
    private static void history(CommandSourceStack source,String text,long before){UUID id=parse(source,text);if(id==null)return;
        if(before==0&&com.roften.avilixlogger.net.CartHistoryNetwork.open(source,id.toString()))return;
        chatHistory(source,text,before);
    }
    private static Component copyButton(String label,String value){return Component.literal(label).withStyle(s->s.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD,value)).withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.literal("Скопировать: "+value))));}
    private static void chatHistory(CommandSourceStack source,String text,long before){UUID id=parse(source,text);if(id==null)return;
        LogQuery q=new LogQuery();q.dim="*";q.constructionId=id;q.limit=8;q.beforeId=before;
        var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{try{var rows=storage.queryReverse(q);source.getServer().execute(()->{
            source.sendSystemMessage(Component.literal("История конструкции ").append(copyButton(id.toString(),id.toString())).withStyle(ChatFormatting.GOLD));
            for(var row:rows){var phase=CreateContraptionKind.phase(row.source);
                boolean transfer=row.type==ActionType.CART_TRANSFER&&phase.equals("automation_transfer")||row.type==ActionType.CREATE_DEPLOYER_ACTION;
                boolean structure=row.type==ActionType.CART_DISASSEMBLE&&phase.equals("structural_disassemble");
                var checkpointPrefix=CartAuditContext.prefix(id)+phase+":";
                boolean checkpoint=CreateContraptionKind.fromSource(row.source)==CreateContraptionKind.MINECART&&CartRollbackPlan.checkpoint(row.type)
                        &&row.source.startsWith(checkpointPrefix)&&row.source.length()>checkpointPrefix.length()&&Character.isDigit(row.source.charAt(checkpointPrefix.length()));
                String suffix=checkpoint?" [контрольная точка]":"";
                var message=Component.literal("entry="+row.id+" · "+java.time.Instant.ofEpochMilli(row.ts)+" · "+LogText.actionLabel(row.type)+" · "+Objects.toString(row.actorName,"SYSTEM")+" · "+row.dim+" "+row.x+" "+row.y+" "+row.z+suffix+" · "+Objects.toString(row.extra,""));
                if(transfer||structure||checkpoint)message.withStyle(st->st.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,(transfer?"/log cart undo-transfer ":structure?"/log cart undo-disassembly ":"/log cart rollback ")+id+" "+row.id)));
                source.sendSystemMessage(message);}
            if(rows.size()==8)source.sendSystemMessage(Component.literal("[Ещё]").withStyle(st->st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log cart chat "+id+" "+rows.getLast().id))));
        });}catch(Exception e){error(source,e);}});
    }
    private static void error(CommandSourceStack s,Exception e){s.getServer().execute(()->s.sendFailure(Component.literal("История Create: "+e.getMessage())));}
}
