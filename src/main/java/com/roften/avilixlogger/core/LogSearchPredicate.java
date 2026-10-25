package com.roften.avilixlogger.core;

import java.util.Locale;

/** Compatibility only: old unified rows contain compressed JSON, unlike SQL-searchable modern tables. */
public final class LogSearchPredicate {
    private LogSearchPredicate() {}
    public static boolean matches(LogEntry e, LogQuery q) {
        if(e == null) return false;
        if(q.exactId>0 && e.id!=q.exactId)return false;
        if(q.constructionId != null && !q.constructionId.equals(CreateContraptionKind.id(e.source))) return false;
        if(q.sourcePrefix != null && (e.source == null || !e.source.startsWith(q.sourcePrefix))) return false;
        if(q.cartId != null && !q.cartId.equals(CartAuditContext.id(e.source))) return false;
        if(q.backpackId != null && !q.backpackId.equals(BackpackIds.fromSource(e.source))) return false;
        if(!anyAliases(q.blockIdFilter,q.blockAliases,e.blockBefore,e.blockAfter,e.source,e.extra)) return false;
        if(!any(q.planeNameFilter,e.entityType,e.entityNbt,e.itemStackNbt,e.extra,e.source)) return false;
        if(!any(q.owner,e.extra,e.entityNbt,e.itemStackNbt)) return false;
        if(!any(q.extraTextFilter,e.source,e.extra,e.entityType)) return false;
        if(!any(q.trainNameFilter,e.extra)) return false;
        if(!any(q.backpackNeedle,e.extra)) return false;
        if(q.planeOnly && !dedicatedPlane(e.type) && !anyPlane(e.entityType,e.extra)) return false;
        return anyAliases(q.textFilter,q.textAliases,e.actorName,e.source,e.extra,e.blockBefore,e.blockAfter,e.entityType,e.entityNbt,e.itemStackNbt,LogText.actionLabel(e.type));
    }
    public static boolean dedicatedPlane(ActionType type) {
        return type == ActionType.PLANE_PLACE || type == ActionType.PLANE_REMOVE || type == ActionType.PLANE_MOUNT || type == ActionType.PLANE_PICKUP || type == ActionType.ENTITY_OWNER_SET;
    }
    private static boolean anyAliases(String needle,java.util.List<String> ids,String... values){
        if(any(needle,values))return true;for(var id:ids)if(any(id,values))return true;return false;
    }
    private static boolean anyPlane(String... values){return any("aircraft",values)||any("airplane",values)||any("plane",values);}
    private static boolean any(String needle,String... values){
        if(needle==null||needle.isBlank())return true;
        String lower=needle.trim().toLowerCase(Locale.ROOT);
        for(String value:values)if(value!=null&&value.toLowerCase(Locale.ROOT).contains(lower))return true;
        return false;
    }
}
