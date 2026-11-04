package com.roften.avilixlogger.core;

import java.util.*;
import java.util.function.*;

/** Runs on the page worker. DB filtering happens before LIMIT; no UI predicate drops fetched rows. */
public final class GuiPageLoader {
    private static final long MAX_RETAINED_BYTES=8L*1024*1024;
    public record Slice(List<LogEntry> entries, boolean exhausted) {}
    private GuiPageLoader() {}
    public static Slice load(LogStorage storage,LogQuery query,int size,boolean aggregated,BooleanSupplier cancelled,Predicate<List<LogEntry>> enoughGroups) {
        int desired=size+1;
        int batch=aggregated?Math.min(512,Math.max(256,desired*8)):desired;
        var entries=new ArrayList<LogEntry>();long cursor=query.chronological?query.afterId:query.beforeId, cursorTs=query.cursorTs,bytes=0;boolean exhausted=false;
        while(!cancelled.getAsBoolean()&&entries.size()<32000){
            var page=query.copy();if(query.chronological)page.afterId=cursor;else page.beforeId=cursor;page.cursorTs=cursorTs;page.limit=Math.min(batch,32000-entries.size());
            var raw=query.chronological?storage.query(page):storage.queryReverse(page);
            if(raw==null||raw.isEmpty()){exhausted=true;break;}
            long next=raw.getLast().id;
            if(next<=0||cursor>0&&(!query.chronological?next>=cursor:raw.getLast().ts<cursorTs||raw.getLast().ts==cursorTs&&next<=cursor))throw new IllegalStateException("БД вернула неверный курсор страницы");
            boolean memoryLimit=false;
            for(var row:raw){
                long weight=PayloadSizeEstimator.estimate(row);
                // Never skip a row: unconsumed results are fetched again after the last
                // displayed cursor. One oversized row is still available by itself.
                if(!entries.isEmpty()&&weight>MAX_RETAINED_BYTES-bytes){memoryLimit=true;break;}
                entries.add(row);bytes+=weight;cursor=row.id;cursorTs=row.ts;
            }
            exhausted=!memoryLimit&&raw.size()<page.limit;
            if(memoryLimit)break;
            if(exhausted||(!aggregated?entries.size()>=desired:enoughGroups.test(entries)))break;
        }
        return new Slice(List.copyOf(entries),exhausted);
    }
}
