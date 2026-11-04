package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.GuiTimeFilter;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** Regression for sparse matches, cursor pages, cancellation and strict calendar inputs. */
final class GuiSearchTest {
    static void run() throws Exception {
        var now=ZonedDateTime.of(2026,10,13,3,29,0,0,ZoneId.of("Europe/Moscow"));
        for(String value:List.of("13.10.2026 03:00","2026-10-13T03:00","03:00"))check(GuiTimeFilter.parse(value,now).since()==Instant.parse("2026-10-13T00:00:00Z").toEpochMilli(),"absolute time/zone mismatch: "+value);
        check(GuiTimeFilter.parse("13.10.2026",now).since()==Instant.parse("2026-10-12T21:00:00Z").toEpochMilli(),"date did not start at journal midnight");
        check(GuiTimeFilter.parse("13.10",now).since()==GuiTimeFilter.parse("13.10.2026",now).since(),"current-year date");
        var range=GuiTimeFilter.parse("12.10.2026..13.10.2026",now);check(range.until()==Instant.parse("2026-10-13T21:00:00Z").toEpochMilli()-1,"upper date excluded that day's events");
        check(GuiTimeFilter.parse("30m",now).minutes()==30&&GuiTimeFilter.parse("2h",now).minutes()==120&&GuiTimeFilter.parse("7d",now).minutes()==10080&&GuiTimeFilter.parse("",now).minutes()==-1,"duration/preset compatibility");
        for(String value:List.of("31.02.2026","25:99","2026-10-13..2026-10-12","2026-10-13..","999999999d","-1h","junk"))reject(()->GuiTimeFilter.parse(value,now),"invalid time silently broadened query: "+value);
        reject(()->GuiTimeFilter.parse("2026-03-29 02:30",now.withZoneSameInstant(ZoneId.of("Europe/Berlin"))),"DST gap silently changed time");
        var names=List.of(new LocalizedSearchTerms.Name("minecraft:netherrack","Незерак"),new LocalizedSearchTerms.Name("minecraft:stone","Камень"));
        var ids=LocalizedSearchTerms.resolve("НеЗеРаК",names);check(ids.equals(List.of("minecraft:netherrack")),"localized name was not resolved");
        var localQuery=new LogQuery();localQuery.textFilter="Незерак";localQuery.textAliases=ids;
        var localRow=row("unrelated");localRow.blockBefore="minecraft:netherrack";
        check(LogSearchPredicate.matches(localRow,localQuery)&&localQuery.copy().textAliases.equals(ids),"translated name did not match ID or copy lost aliases");
        localQuery.blockIdFilter="Незерак";localQuery.blockAliases=ids;check(LogSearchPredicate.matches(localRow,localQuery),"localized block field did not match");
        localQuery.actorName="another-filter";localRow.blockBefore="minecraft:stone";check(!LogSearchPredicate.matches(localRow,localQuery),"localized query broadened to unrelated IDs");
        reject(()->LocalizedSearchTerms.checked(List.of("bad'); DROP TABLE logs")),"invalid registry alias accepted");
        var broad=new ArrayList<LocalizedSearchTerms.Name>();for(int i=0;i<257;i++)broad.add(new LocalizedSearchTerms.Name("test:item"+i,"широкое имя"));reject(()->LocalizedSearchTerms.resolve("широкое",broad),"broad name silently truncated matching IDs");
        var executor=Executors.newSingleThreadExecutor(r->new Thread(r,"GUI-search-test-worker"));
        try{
            List<LogEntry> data=new ArrayList<>();for(int i=4000;i>0;i--){var row=row(i%31==0?"needle Express owner:alice":"unrelated");row.id=i;row.type=ActionType.BLOCK_PLACE;row.blockAfter="minecraft:stone";data.add(row);}
            var reads=new AtomicInteger();var q=new LogQuery();q.textFilter="needle";q.trainNameFilter="Express";q.owner="alice";q.blockIdFilter="stone";q.dim="*";
            LogStorage database=new LogStorage(){public void append(LogEntry e){}public void shutdown(){}public List<LogEntry> query(LogQuery x){throw new AssertionError();}public List<LogEntry> queryReverse(LogQuery x){
                check(Thread.currentThread().getName().equals("GUI-search-test-worker"),"page work ran on caller/server thread");reads.incrementAndGet();
                return data.stream().filter(e->(x.beforeId<=0||e.id<x.beforeId)&&LogSearchPredicate.matches(e,x)).limit(x.limit).toList();
            }};
            var first=executor.submit(()->GuiPageLoader.load(database,q,60,false,()->false,null)).get(3,TimeUnit.SECONDS);
            check(first.entries().size()==61&&!first.exhausted()&&reads.get()==1,"raw page overfetched or filtered after LIMIT");
            q.beforeId=first.entries().get(59).id;
            var second=executor.submit(()->GuiPageLoader.load(database,q,60,false,()->false,null)).get(3,TimeUnit.SECONDS);
            check(second.entries().size()==61&&second.entries().getFirst().id==first.entries().get(60).id,"next page skipped lookahead or underfilled sparse matches");
            Set<Long> shown=new HashSet<>();first.entries().subList(0,60).forEach(e->shown.add(e.id));check(second.entries().subList(0,60).stream().noneMatch(e->shown.contains(e.id)),"cursor duplicated shown rows");
            q.beforeId=second.entries().get(59).id;var last=executor.submit(()->GuiPageLoader.load(database,q,60,false,()->false,null)).get(3,TimeUnit.SECONDS);check(last.exhausted()&&last.entries().size()==9,"end page incorrect");
            int count=reads.get();var canceled=GuiPageLoader.load(database,q,60,false,()->true,null);check(canceled.entries().isEmpty()&&reads.get()==count,"obsolete request still read DB");
            q.beforeId=0;var aggregate=executor.submit(()->GuiPageLoader.load(database,q,10,true,()->false,rows->rows.size()>90)).get(3,TimeUnit.SECONDS);check(aggregate.entries().size()==129&&aggregate.exhausted(),"grouped refill lost filtered records");
            check(q.copy().textFilter.equals(q.textFilter)&&q.copy().owner.equals(q.owner),"query copy lost search filters");
            System.out.println("GUI_SEARCH_OK: strict date/time/range/timezone/DST, durations, sparse full pages, cursor/lookahead/end, cancellation, worker isolation and grouped refill");
        }finally{executor.shutdownNow();}
        var large=new ArrayList<LogEntry>();String text="payload".repeat(150000);
        for(int i=10;i>0;i--){var row=row(text);row.id=i;large.add(row);}
        var source=new LogStorage(){public void append(LogEntry e){}public void shutdown(){}public List<LogEntry> query(LogQuery q){return queryReverse(q);}public List<LogEntry> queryReverse(LogQuery q){return large.stream().filter(e->q.beforeId==0||e.id<q.beforeId).limit(q.limit).toList();}};
        var boundedQuery=new LogQuery();var seen=new ArrayList<Long>();boolean done=false;
        while(!done){var slice=GuiPageLoader.load(source,boundedQuery,60,false,()->false,null);check(!slice.entries().isEmpty(),"memory-limited page made no progress");seen.addAll(slice.entries().stream().map(e->e.id).toList());boundedQuery.beforeId=slice.entries().getLast().id;done=slice.exhausted();check(seen.size()<=10,"memory-limit pagination looped");}
        check(seen.equals(large.stream().map(e->e.id).toList()),"memory limit skipped/duplicated query rows");
        check(GuiPageLoader.load(source,new LogQuery(),60,false,()->false,null).entries().size()<large.size(),"GUI retained every oversized row");
    }
    private static void reject(Runnable action,String message){try{action.run();}catch(IllegalArgumentException expected){return;}throw new AssertionError(message);}
}
