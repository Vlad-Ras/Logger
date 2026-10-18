package com.roften.avilixlogger.core;

import java.util.*;
import java.util.function.*;

/** Runs on the page worker. DB filtering happens before LIMIT; no UI predicate drops fetched rows. */
public final class GuiPageLoader {
    public record Slice(List<LogEntry> entries, boolean exhausted) {}
    private GuiPageLoader() {}
    public static Slice load(LogStorage storage,LogQuery query,int size,boolean aggregated,BooleanSupplier cancelled,Predicate<List<LogEntry>> enoughGroups) {
        int desired=size+1;
        int batch=aggregated?Math.min(3200,Math.max(256,desired*8)):desired;
        var entries=new ArrayList<LogEntry>();long cursor=query.beforeId;boolean exhausted=false;
        while(!cancelled.getAsBoolean()&&entries.size()<32000){
            var page=query.copy();page.beforeId=cursor;page.limit=Math.min(batch,32000-entries.size());
            var raw=storage.queryReverse(page);
            if(raw==null||raw.isEmpty()){exhausted=true;break;}
            long next=raw.getLast().id;
            if(next<=0||cursor>0&&next>=cursor)throw new IllegalStateException("БД вернула неверный курсор страницы");
            entries.addAll(raw);cursor=next;
            exhausted=raw.size()<page.limit;
            if(exhausted||(!aggregated?entries.size()>=desired:enoughGroups.test(entries)))break;
        }
        return new Slice(List.copyOf(entries),exhausted);
    }
}
