package com.roften.avilixlogger.core;
import java.util.*;
final class EntryPositionResolverTest {
    static void run(){
        long id=1880834017623801859L;
        var feed=new LogEntry();feed.id=id;feed.type=ActionType.BLOCK_INTERACT;feed.dim="minecraft:the_nether";feed.x=-1020;feed.y=15;feed.z=-820;
        var sink=new Sink(null,feed);var pos=EntryPositionResolver.resolve(sink,id);
        check(pos!=null&&pos.dimension().equals(feed.dim)&&pos.x()==-1020&&pos.y()==15&&pos.z()==-820,"feed-only selected row lost position");
        check(sink.queries.size()==2&&sink.queries.get(0).requireDetails&&!sink.queries.get(1).requireDetails,"details/feed read order changed");
        for(var q:sink.queries)check(q.exactId==id&&q.dim.equals("*")&&q.sinceTs==0&&q.untilTs==Long.MAX_VALUE&&q.limit==1,"lookup scanned neighbours or current dimension/time");
        var unknown=feed.copyForQueue();unknown.beAfter="{PositionKnown:false}";
        sink=new Sink(unknown,feed);check(EntryPositionResolver.resolve(sink,id)==null&&sink.queries.size()==1,"feed overrode explicit unknown position");
        var neighbour=feed.copyForQueue();neighbour.id=id-1;
        check(EntryPositionResolver.resolve(new Sink(null,neighbour),id)==null,"teleport guessed a neighbouring entry");
        var claim=feed.copyForQueue();claim.type=ActionType.CLAIM_ACTION;
        check(EntryPositionResolver.resolve(new Sink(null,claim),id)==null,"claim invented a chunk without evidence");
        System.out.println("ENTRY_POSITION_RESOLVER_OK: exact Nether coordinates, feed-only row, authoritative unknown marker and no neighbour/chunk guessing");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Sink implements LogStorage {
        final LogEntry detail,feed;final List<LogQuery> queries=new ArrayList<>();
        Sink(LogEntry detail,LogEntry feed){this.detail=detail;this.feed=feed;}
        public List<LogEntry> query(LogQuery q){queries.add(q.copy());var row=q.requireDetails?detail:feed;return row==null?List.of():List.of(row);}
        public List<LogEntry> queryReverse(LogQuery q){return query(q);}public void append(LogEntry e){}public void shutdown(){}
    }
}
