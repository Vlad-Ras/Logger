package com.roften.avilixlogger.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.arguments.*;
import com.roften.avilixlogger.compat.backpacked.*;
import com.roften.avilixlogger.core.*;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.*;
import java.util.*;
import static net.minecraft.commands.Commands.*;

public final class BackpackCommands {
    private BackpackCommands() {}
    public static LiteralArgumentBuilder<CommandSourceStack> node(){
        var node=literal("backpack").requires(s->BackpackSupport.available()&&PermissionUtil.has(s,"avilixlogger.command.lookup",2))
            .executes(c->{c.getSource().sendSystemMessage(Component.literal("/log backpack held | find <владелец> | locate <ID> | history <ID> [before] | recover <ID> <игрок> | return <ID> <игрок> | confirm | cancel"));return 1;})
            .then(literal("find").then(argument("owner",StringArgumentType.word()).executes(c->{find(c.getSource(),StringArgumentType.getString(c,"owner"));return 1;})))
            .then(literal("held").executes(c->{var p=c.getSource().getPlayerOrException();BackpackAudit.trackPlayer(p,ActionType.BACKPACK_STATE);UUID id=BackpackIds.id(p.getMainHandItem());if(id==null)c.getSource().sendFailure(Component.literal("В руке нет учтённого рюкзака"));else locate(c.getSource(),id);return 1;}))
            .then(literal("locate").then(argument("id",StringArgumentType.word()).executes(c->{var id=resolve(c.getSource(),StringArgumentType.getString(c,"id"));if(id!=null)locate(c.getSource(),id);return 1;})))
            .then(literal("history").then(argument("id",StringArgumentType.word()).executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),0);return 1;})
                .then(argument("before",LongArgumentType.longArg(1)).executes(c->{history(c.getSource(),StringArgumentType.getString(c,"id"),LongArgumentType.getLong(c,"before"));return 1;}))))
            .then(literal("confirm").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2)).executes(c->{BackpackRecovery.confirm(c.getSource());return 1;}))
            .then(literal("cancel").executes(c->{BackpackRecovery.cancel(c.getSource());return 1;}));
        for(boolean recover:new boolean[]{true,false})node.then(literal(recover?"recover":"return").requires(s->PermissionUtil.has(s,"avilixlogger.command.rollback",2))
            .then(argument("id",StringArgumentType.word()).then(argument("player",EntityArgument.player()).executes(c->{BackpackRecovery.prepare(c.getSource(),StringArgumentType.getString(c,"id"),EntityArgument.getPlayer(c,"player"),recover);return 1;}))));
        return node;
    }
    public static UUID resolve(CommandSourceStack source,String text){var index=BackpackIndex.get(source.getServer());UUID id=index==null?null:index.resolve(text);if(id==null)source.sendFailure(Component.literal("Неизвестный UUID или короткий ID рюкзака"));return id;}
    private static void find(CommandSourceStack source,String owner){var index=BackpackIndex.get(source.getServer());if(index==null)return;var rows=index.owned(owner);for(var r:rows.stream().limit(100).toList())locate(source,r.id);if(rows.isEmpty())source.sendSystemMessage(Component.literal("Рюкзаки первого владельца "+owner+" не найдены"));if(rows.size()>100)source.sendSystemMessage(Component.literal("Показаны последние 100 рюкзаков"));}
    private static void locate(CommandSourceStack source,UUID id){var index=BackpackIndex.get(source.getServer());var r=index==null?null:index.record(id);if(r==null)return;
        // Resolve a present online holder's current position without global inventory polling.
        var holder=r.holder==null?null:source.getServer().getPlayerList().getPlayer(r.holder);
        String address=holder==null?r.dim+" "+r.x+" "+r.y+" "+r.z:holder.level().dimension().location()+" "+holder.blockPosition().toShortString();
        source.sendSystemMessage(Component.literal(r.shortId+" · "+id+" · владелец: "+r.ownerName+" · держатель: "+r.holderName+" · "+r.state+" · "+r.kind+" · "+address+" · "+r.reason).withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log backpack history "+id))));
    }
    private static void history(CommandSourceStack source,String text,long before){UUID resolved;try{resolved=UUID.fromString(text);}catch(IllegalArgumentException e){resolved=resolve(source,text);}if(resolved==null)return;UUID id=resolved;LogQuery q=new LogQuery();q.dim="*";q.backpackId=id;q.beforeId=before;q.limit=25;
        var storage=LoggerRuntime.storage(source.getLevel());BackpackRecovery.query(source,()->{try{var rows=storage.queryReverse(q);source.getServer().execute(()->{
            source.sendSystemMessage(Component.literal("История рюкзака "+text));for(var row:rows)source.sendSystemMessage(Component.literal(row.id+" · "+java.time.Instant.ofEpochMilli(row.ts)+" · "+LogText.actionLabel(row.type)+" · "+Objects.toString(row.actorName,"SYSTEM")+" · "+Objects.toString(row.extra,"")+" · "+row.dim+" "+row.x+" "+row.y+" "+row.z));
            if(rows.size()==25)source.sendSystemMessage(Component.literal("[Ещё]").withStyle(s->s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,"/log backpack history "+id+" "+rows.getLast().id))));
        });}catch(Exception e){source.getServer().execute(()->source.sendFailure(Component.literal("История недоступна: "+e.getMessage())));}});
    }
}
