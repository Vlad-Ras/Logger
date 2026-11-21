package com.roften.avilixlogger.core;

import java.time.*;
import java.util.*;

final class RecoveryTimeInputTest {
    static void run() {
        var moscow=ZoneId.of("Europe/Moscow");
        var now=ZonedDateTime.of(2026,11,21,4,0,0,0,moscow);
        var window=RecoveryTimeInput.form(true,"21.11.2026","03:30","21.11.2026","03:44",now);
        check(window.from()==Instant.parse("2026-11-21T00:30:00Z").toEpochMilli(),"GUI server timezone shifted beginning");
        check(window.until()==Instant.parse("2026-11-21T00:44:59.999Z").toEpochMilli(),"typed end minute lost inclusive seconds");
        var flags=window.flags(RecoveryTimeInput.Area.RADIUS,"20","Steve,Alex");
        check(flags.contains("--at 2026-11-21T00:30:00Z --until 2026-11-21T00:44:59.999Z")&&flags.endsWith("--r 20 --p Steve,Alex"),"form sent ambiguous local text");
        check(RecoveryTimeInput.absolute("2026-11-21T03:30+03:00",ZoneId.of("UTC")).equals(window.from()),"offset was ignored");
        check(RecoveryTimeInput.absolute("2026-11-21T03:30",moscow).equals(window.from()),"local command stopped using server zone");
        check(RecoveryTimeInput.absolute("2026-03-29T02:30",ZoneId.of("Europe/Berlin"))==null,"nonexistent DST time shifted silently");
        check(RecoveryTimeInput.absolute("2026-10-25T02:30",ZoneId.of("Europe/Berlin"))==null,"ambiguous DST time accepted without offset");
        var moment=RecoveryTimeInput.form(false,"21.11.2026","03:30","","",now);
        check(!moment.flags(RecoveryTimeInput.Area.WORLDEDIT,"0","").contains("--until"),"state-at mode turned into replay period");
        reject(()->window.flags(RecoveryTimeInput.Area.RADIUS,"0",""));
        reject(()->window.flags(RecoveryTimeInput.Area.BLOCK,"20","Steve --world"));
        reject(()->RecoveryTimeInput.form(true,"21.11.2026","04:00","21.11.2026","04:01",now));
        boundary();
        System.out.println("RECOVERY_TIME_INPUT_OK: explicit zones, inclusive replay period, strict state-at boundary, malformed scope/player/DST/future refusal");
    }
    private static void boundary() {
        var older=AsyncPipelineTest.row("boundary old");older.ts=1000;older.id=1;older.type=ActionType.BLOCK_PLACE;
        older.blockBefore="{Name:'minecraft:air'}";older.blockAfter="{Name:'minecraft:stone'}";
        var newer=older.copyForQueue();newer.ts=1001;newer.id=2;newer.blockBefore=older.blockAfter;newer.blockAfter="{Name:'minecraft:dirt'}";
        var history=new LogStorage() {
            public void append(LogEntry row) {throw new UnsupportedOperationException();}
            public List<LogEntry> query(LogQuery query) {throw new UnsupportedOperationException();}
            public List<LogEntry> queryReverse(LogQuery query) {
                check(query.actorName==null&&query.actorNames.equals(List.of("Steve","Alex")),"ordinary rollback treated multiple actors as one nickname");
                return List.of(newer,older).stream().filter(row->row.ts>=query.sinceTs&&row.ts<=query.untilTs&&(query.beforeId<=0||row.id<query.beforeId)).toList();
            }
            public void shutdown() {}
        };
        var result=RollbackEngine.prepareBoxRange(history,"minecraft:overworld",net.minecraft.core.BlockPos.ZERO,net.minecraft.core.BlockPos.ZERO,1000,2000,"Steve,Alex",null);
        check(result.blocks().size()==1&&result.blocks().getFirst().stateTag().getString("Name").equals("minecraft:stone"),"state at T undid the event occurring exactly at T");
    }
    private static void reject(Runnable action) {try{action.run();throw new AssertionError("invalid recovery form accepted");}catch(IllegalArgumentException expected){}}
    private static void check(boolean value,String message) {if(!value)throw new AssertionError(message);}
}
