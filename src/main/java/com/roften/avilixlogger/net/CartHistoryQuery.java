package com.roften.avilixlogger.net;

import com.roften.avilixlogger.core.*;
import java.util.*;

/** The same search predicates apply before LIMIT and on every cursor page. */
public final class CartHistoryQuery {
    private CartHistoryQuery(){}
    public static LogQuery build(C2SCartHistoryPayload p){
        var q=new LogQuery();q.dim="*";q.cartId=p.cartId().isBlank()?null:UUID.fromString(p.cartId());q.sourcePrefix="create:cart:";
        if(p.before()<0||p.actor().length()>64)throw new IllegalArgumentException("Неверный фильтр истории");
        q.actorName=p.actor().isBlank()?null:p.actor().trim();
        if(p.checkpointsOnly())q.types=EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE,ActionType.CART_ROLLBACK);
        q.sinceTs=0;q.untilTs=Long.MAX_VALUE;q.beforeId=p.before();q.requireDetails=true;q.limit=13;return q;
    }
}
