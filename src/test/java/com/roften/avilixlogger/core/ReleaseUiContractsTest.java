package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.GuiTimeFilter;
import java.time.*;
import java.util.*;

/** Standalone checks for calendar bounds and stable query IDs, without loading Minecraft. */
public final class ReleaseUiContractsTest {
    public static void main(String[] args){run();System.out.println("RELEASE_UI_CONTRACTS_OK");}
    static void run() {
        var now=ZonedDateTime.of(2026,11,2,15,30,0,0,ZoneId.of("Europe/Moscow"));
        var range=GuiTimeFilter.parse(GuiTimeFilter.rangeInput("01.11.2026","09:30","02.11.2026","10:15",now),now);
        check(range.since()==Instant.parse("2026-11-01T06:30:00Z").toEpochMilli(),"lower time or timezone changed");
        check(range.until()==Instant.parse("2026-11-02T07:16:00Z").toEpochMilli()-1,"typed upper minute was cut off");
        var day=GuiTimeFilter.parse(GuiTimeFilter.rangeInput("01.11.2026","","01.11.2026","",now),now);
        check(day.until()-day.since()==86_400_000-1,"whole day inclusive bounds");
        var until=GuiTimeFilter.parse(GuiTimeFilter.rangeInput("02.11.2026","09:30:15","02.11.2026","09:30:15",now),now);
        check(until.until()-until.since()==999,"typed second was cut off");
        check(GuiTimeFilter.parse(GuiTimeFilter.rangeInput("","","","",now),now).minutes()==0,"all history selection");
        reject(()->GuiTimeFilter.rangeInput("31.11.2026","","","",now));
        reject(()->GuiTimeFilter.rangeInput("02.11.2026","12:00","01.11.2026","12:00",now));
        reject(()->GuiTimeFilter.rangeInput("02.11.2026","25:00","","",now));
        var minute=GuiTimeFilter.parse(GuiTimeFilter.form(GuiTimeFilter.Mode.MINUTE,"02.11.2026","13:32","","",now),now);
        var later=Instant.parse("2026-11-02T10:48:00Z").toEpochMilli();
        check(minute.until()-minute.since()==59_999&&later>minute.until(),"exact minute admitted 13:48 or cut off seconds");
        var since=GuiTimeFilter.parse(GuiTimeFilter.form(GuiTimeFilter.Mode.SINCE,"02.11.2026","13:32","","",now),now);
        check(since.since()<later&&since.until()==-1,"since mode changed its lower-bound meaning");
        var upper=GuiTimeFilter.parse(GuiTimeFilter.form(GuiTimeFilter.Mode.UNTIL,"","","02.11.2026","13:32",now),now);
        check(upper.since()==0&&upper.until()==minute.until(),"upper-only time was ignored");
        reject(()->GuiTimeFilter.form(GuiTimeFilter.Mode.MINUTE,"02.11.2026","13:32:10","","",now));
        var berlin=now.withZoneSameInstant(ZoneId.of("Europe/Berlin"));
        reject(()->GuiTimeFilter.rangeInput("29.03.2026","02:30","","",berlin));
        var ids=new HashSet<Integer>();var keys=new HashSet<String>();
        for(var module:ModLogFilters.MODULES) {
            check(module.preset()>=13&&ids.add(module.preset()),"module presets collide");
            check(keys.add(module.key())&&!module.sourcePrefix().contains(" ")&&module.sourcePrefix().endsWith(":"),"invalid module filter");
            check(ModLogFilters.find(module.preset()).equals(module),"unstable module lookup");
        }
        check(ModLogFilters.find(0)==null&&ModLogFilters.find(12)==null&&ModLogFilters.find(26)==null,"legacy or invalid ID became a module");
        for(var module:ModLogFilters.MODULES)for(int option:ModLogFilters.options(module))check(ModLogFilters.find(option).equals(module),"subfilter lost its module");
        check(ModLogFilters.types(1302).contains(ActionType.ME_CRAFT_FINISH) && !ModLogFilters.types(1302).contains(ActionType.ME_TAKE),"craft filter includes resources");
        var chunks=new LogQuery();chunks.dim="minecraft:overworld";
        ChunkLogSearch.radius(chunks,new net.minecraft.core.BlockPos(-1,180,31),0,0);
        check(chunks.minPos.getX()==-16&&chunks.maxPos.getX()==-1&&chunks.minPos.getZ()==16&&chunks.maxPos.getZ()==31&&chunks.minPos.getY()==Integer.MIN_VALUE,"negative/current chunk or height mismatch");
        chunks.includeIntersectingClaims=true;check(chunks.copy().includeIntersectingClaims,"spatial scope lost in fallback copy");
        check(ModLogFilters.find(14).sourcePrefix().equals("mod:openpartiesandclaims:"),"OPAC scope mismatch");
        check(ModLogFilters.find(20).sourcePrefix().equals("mod:create_vibrant_vaults:"),"Vibrant Vaults scope mismatch");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid time accepted");}catch(IllegalArgumentException expected){}}
}
