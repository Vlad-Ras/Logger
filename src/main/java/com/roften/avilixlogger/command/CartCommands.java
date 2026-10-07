package com.roften.avilixlogger.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.ChatFormatting;
import net.neoforged.fml.ModList;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class CartCommands {
    private CartCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return literal("cart").requires(s->ModList.get().isLoaded("create") && PermissionUtil.has(s,"avilixlogger.command.lookup",2))
            .executes(c->{c.getSource().sendSystemMessage(Component.literal("/log cart find <игрок> | history <ID> [before] | held | rollback <ID> <запись> | confirm | cancel"));return 1;})
            .then(literal("find").then(argument("player",StringArgumentType.word()).executes(c->{find(c.getSource(),StringArgumentType.getString(c,"player"));return 1;})))
            .then(literal("history").then(argument("id",StringArgumentType.word())
                .executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;})
                .then(argument("before",LongArgumentType.longArg(1)).executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),LongArgumentType.getLong(c,"before"));return 1;}))))
            .then(literal("held").executes(c->{var p=c.getSource().getPlayerOrException();UUID id=CreateCartAudit.itemId(p.getMainHandItem());if(id==null)c.getSource().sendFailure(Component.literal("В основной руке нет учтённой вагонеточной конструкции"));else history(c.getSource(),id.toString(),0);return 1;}))
            .then(literal("rollback").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
                .then(argument("id",StringArgumentType.word()).then(argument("entry",LongArgumentType.longArg(1)).executes(c->{UUID id=parse(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)CartRollbackCoordinator.preview(c.getSource(),id,LongArgumentType.getLong(c,"entry"));return 1;}))))
            .then(literal("confirm").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CartRollbackCoordinator.confirm(c.getSource());return 1;}))
            .then(literal("cancel").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{CartRollbackCoordinator.cancel(c.getSource());return 1;}));
    }
    private static UUID parse(CommandSourceStack s,String value){try{return UUID.fromString(value);}catch(IllegalArgumentException e){s.sendFailure(Component.literal("Неверный ID конструкции"));return null;}}
    private static void find(CommandSourceStack source,String player){
        LogQuery q=new LogQuery();q.dim="*";q.actorName=player;q.limit=200;q.sinceTs=0;
        q.types=EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE);
        var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{try{
            var rows=storage.queryReverse(q);
            source.getServer().execute(()->{Set<UUID> seen=new HashSet<>();for(var row:rows){UUID id=CartAuditContext.id(row.source);if(id==null||!seen.add(id))continue;
                source.sendSystemMessage(Component.literal(id+" · "+row.type+" · "+row.dim+" "+row.x+" "+row.y+" "+row.z).withStyle(st->st.withColor(ChatFormatting.AQUA).withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log cart history "+id))));}
                if(seen.isEmpty())source.sendSystemMessage(Component.literal("Конструкции по действиям "+player+" не найдены"));else source.sendSystemMessage(Component.literal("По последним 200 событиям игрока. Нажмите ID для истории."));});
        }catch(Exception e){error(source,e);}});
    }
    private static void history(CommandSourceStack source,String text,long before){UUID id=parse(source,text);if(id==null)return;
        LogQuery q=new LogQuery();q.dim="*";q.cartId=id;q.limit=25;q.beforeId=before;
        var storage=LoggerRuntime.storage(source.getLevel());
        CartRollbackCoordinator.query(source,()->{try{var rows=storage.queryReverse(q);source.getServer().execute(()->{
            source.sendSystemMessage(Component.literal("История конструкции "+id).withStyle(ChatFormatting.GOLD));
            for(var row:rows){String suffix=CartRollbackPlan.checkpoint(row.type)?" [контрольная точка]":"";
                source.sendSystemMessage(Component.literal(row.id+" · "+java.time.Instant.ofEpochMilli(row.ts)+" · "+row.type+" · "+Objects.toString(row.actorName,"SYSTEM")+" · "+row.dim+" "+row.x+" "+row.y+" "+row.z+suffix+" · "+Objects.toString(row.extra,"")).withStyle(st->st.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,"/log cart rollback "+id+" "+row.id))));}
            if(rows.size()==25)source.sendSystemMessage(Component.literal("[Ещё]").withStyle(st->st.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log cart history "+id+" "+rows.getLast().id))));
        });}catch(Exception e){error(source,e);}});
    }
    private static void error(CommandSourceStack s,Exception e){s.getServer().execute(()->s.sendFailure(Component.literal("История Create: "+e.getMessage())));}
}
