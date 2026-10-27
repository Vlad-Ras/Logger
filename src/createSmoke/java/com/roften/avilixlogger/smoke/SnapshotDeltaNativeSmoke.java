package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Native serializers feed the real ordered pipeline; the sink is isolated and never authorizes Logger. */
public final class SnapshotDeltaNativeSmoke {
    private SnapshotDeltaNativeSmoke(){}
    public static void verify(LogEntry changed,LogEntry noop,LogEntry expected,String label)throws Exception{
        check(changed!=null&&noop!=null&&expected!=null,"native pending evidence absent: "+label);
        long timestamp=changed.ts;var actor=changed.actorUuid;var name=changed.actorName;var dim=changed.dim;int x=changed.x,y=changed.y,z=changed.z;String source=changed.source,extra=changed.extra;
        var rows=new CopyOnWriteArrayList<LogEntry>();
        var sink=new LogStorage(){
            public void append(LogEntry row){check(Thread.currentThread().getName().equals("avilixlogger-event-worker"),"native evidence escaped event worker");rows.add(row);}
            public List<LogEntry> query(LogQuery q){return List.copyOf(rows);}public List<LogEntry> queryReverse(LogQuery q){return List.copyOf(rows);}public void shutdown(){}
        };
        var storage=new AsyncLogStorage(sink,2,256*1024);
        try{
            storage.append(noop);storage.append(changed);
            check(storage.awaitVisible(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)),"native pending evidence did not become visible");
            check(rows.size()==1,"native noop persisted or mutation missing: "+label);var actual=rows.getFirst();
            check(actual.ts==timestamp&&Objects.equals(actual.actorUuid,actor)&&Objects.equals(actual.actorName,name)&&actual.dim.equals(dim)&&actual.x==x&&actual.y==y&&actual.z==z&&Objects.equals(actual.source,source)&&Objects.equals(actual.extra,extra),"native worker read later metadata: "+label);
            check(Objects.equals(actual.beBefore,expected.beBefore)&&Objects.equals(actual.beAfter,expected.beAfter)&&Objects.equals(actual.blockBefore,expected.blockBefore)&&Objects.equals(actual.blockAfter,expected.blockAfter),"native pending pipeline changed full snapshots: "+label);
            var replay=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(actual),LogEntry.class);check(Objects.equals(replay.beBefore,actual.beBefore)&&Objects.equals(replay.beAfter,actual.beAfter),"native queued evidence lost journal data");
        }finally{storage.shutdown();}
        org.slf4j.LoggerFactory.getLogger("AvilixLogger/SnapshotDeltaSmoke").info("SNAPSHOT_DELTA_NATIVE_PIPELINE_OK: {} native full payload, captured metadata, no-op rejection, ordered event worker and journal",label);
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
