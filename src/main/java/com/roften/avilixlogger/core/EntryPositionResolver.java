package com.roften.avilixlogger.core;
import java.util.List;
/** Read-only exact-ID lookup. Missing optional snapshots must not hide a real feed position. */
public final class EntryPositionResolver {
    private EntryPositionResolver(){}
    public static EntryPositionEvidence.Position resolve(LogStorage storage,long id) {
        if(id<=0||id==Long.MAX_VALUE)return null;
        var query=new LogQuery();query.dim="*";query.sinceTs=0;query.untilTs=Long.MAX_VALUE;
        query.exactId=id;query.limit=1;query.requireDetails=true;query.debugSource="entry-position";
        var details=exact(storage.query(query),id);
        // An explicit unknown-position marker is authoritative, even if the feed has x/y/z.
        if(details!=null)return EntryPositionEvidence.read(details);
        query.requireDetails=false;
        return EntryPositionEvidence.read(exact(storage.query(query),id));
    }
    private static LogEntry exact(List<LogEntry> rows,long id){return rows!=null&&rows.size()==1&&rows.getFirst().id==id?rows.getFirst():null;}
}
