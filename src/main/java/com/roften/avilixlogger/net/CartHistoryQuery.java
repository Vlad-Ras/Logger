package com.roften.avilixlogger.net;

import com.roften.avilixlogger.core.*;
import java.util.*;

/** The same search predicates apply before LIMIT and on every cursor page. */
public final class CartHistoryQuery {
    private CartHistoryQuery(){}
    public static EnumSet<ActionType> checkpoints(){return EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE,ActionType.CART_ROLLBACK);}
    public static LogQuery build(C2SCartHistoryPayload p){
        var q=new LogQuery();q.dim="*";q.cartId=p.cartId().isBlank()?null:UUID.fromString(p.cartId());q.sourcePrefix="create:cart:";
        if(p.before()<0||p.actor().length()>64)throw new IllegalArgumentException("Неверный фильтр истории");
        q.actorName=p.actor().isBlank()?null:p.actor().trim();
        if(p.checkpointsOnly())q.types=EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE,ActionType.CART_ROLLBACK);
        q.cartOwner=p.owner().isBlank()?null:p.owner().trim();
        q.dim=p.dimension().isBlank()?"*":p.dimension().trim();q.textFilter=p.text().trim();
        if(!p.actionFilter().equals("ALL")) {
            if(p.actionFilter().equals("STATE"))q.types=checkpoints();
            else if(p.actionFilter().equals("CARGO"))q.types=EnumSet.of(ActionType.CONTAINER_PUT,ActionType.CONTAINER_TAKE,ActionType.CART_CONTENT_CHANGE,ActionType.CART_TRANSFER);
            else {var type=ActionType.valueOf(p.actionFilter());q.types=EnumSet.of(type);}
        }
        var now=java.time.ZonedDateTime.now();var time=GuiTimeFilter.parse(p.time(),now);
        q.sinceTs=time.since()>=0?time.since():time.minutes()>=0?Math.max(0,now.toInstant().toEpochMilli()-time.minutes()*60000L):0;
        q.untilTs=time.until()>=0?time.until():Long.MAX_VALUE;
        q.beforeId=p.before();q.debugSource="cart_gui";q.requireDetails=false;q.limit=p.pageSize()+1;return q;
    }
}
