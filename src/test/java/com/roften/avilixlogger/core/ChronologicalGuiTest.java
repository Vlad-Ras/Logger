package com.roften.avilixlogger.core;

import java.util.*;

/** Equal timestamps and clock-adjusted IDs must paginate without loss or duplicates. */
public final class ChronologicalGuiTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void run() {
        var rows=new ArrayList<LogEntry>();
        for(long[] pair:new long[][]{{90,13_480},{100,13_320},{101,13_320},{102,13_319}}){
            var e=AsyncPipelineTest.row("time");e.id=pair[0];e.ts=pair[1];rows.add(e);
        }
        LogStorage database=new LogStorage(){
            public void append(LogEntry e){} public void shutdown(){}
            public List<LogEntry> queryReverse(LogQuery q){throw new AssertionError("From-time search used newest-first paging");}
            public List<LogEntry> query(LogQuery q){return rows.stream()
                    .filter(e->e.ts>=q.sinceTs&&e.ts<=q.untilTs)
                    .filter(e->q.afterId<=0||e.ts>q.cursorTs||e.ts==q.cursorTs&&e.id>q.afterId)
                    .sorted(Comparator.comparingLong((LogEntry e)->e.ts).thenComparingLong(e->e.id)).limit(q.limit).toList();}
        };
        var q=new LogQuery();q.chronological=true;q.sinceTs=13_320;q.untilTs=14_000;
        var first=GuiPageLoader.load(database,q,1,false,()->false,null);
        check(first.entries().stream().map(e->e.id).toList().equals(List.of(100L,101L)),"first page did not start at requested time");
        q.afterId=100;q.cursorTs=13_320;
        var next=GuiPageLoader.load(database,q.copy(),1,false,()->false,null);
        check(next.entries().stream().map(e->e.id).toList().equals(List.of(101L,90L)),"timestamp tie or clock-adjusted ID broke pagination");
        q.afterId=90;q.cursorTs=13_480;
        check(GuiPageLoader.load(database,q,1,false,()->false,null).entries().isEmpty(),"end of chronological history repeated rows");
        System.out.println("CHRONOLOGICAL_GUI_OK: From boundary, timestamp+ID cursor, equal timestamps, clock adjustment and end page");
    }
}
