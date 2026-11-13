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
        check(range.mode()==GuiTimeFilter.Mode.RANGE,"range labelled as since");
        var narrow=GuiTimeFilter.parse("01.11.2026 14:00..01.11.2026 15:00",now);
        check(narrow.until()==Instant.parse("2026-11-01T12:01:00Z").toEpochMilli()-1,"direct range lost the last minute");
        check(Instant.parse("2026-11-01T20:25:42Z").toEpochMilli()>narrow.until(),"23:25 entered a 14:00–15:00 search");
        check(GuiTimeFilter.parse("01.11.2026 14:00..02.11.2026 15:00:59.999",now).mode()==GuiTimeFilter.Mode.RANGE,
                "saved absolute range is incompatible");
        var exactInput = GuiTimeFilter.parse("от 11.11.2026\u202F14:30", now);
        check(exactInput.since() == Instant.parse("2026-11-11T11:30:00Z").toEpochMilli(), "pasted time lost its lower bound");
        var sameDay = GuiTimeFilter.parse("11.11.2026 22:11..22:12", now);
        check(sameDay.equals(GuiTimeFilter.parse("11.11.2026 22:11..11.11.2026 22:12", now)),
                "second time without a date used today");
        reject(()->GuiTimeFilter.form(GuiTimeFilter.Mode.RANGE,"01.11.2026","14:00","","",now));
        reject(()->GuiTimeFilter.form(GuiTimeFilter.Mode.RANGE,"","","01.11.2026","15:00",now));
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
        var hidden=GuiEventVisibility.ALL_VISIBLE.toggle(14).toggle(32).toggle(ActionType.CHAT_MESSAGE);
        check(hidden.hidden(ActionType.BLOCK_PLACE,"mod:openpartiesandclaims",null),"legacy mod prefix visible");
        check(hidden.hidden(ActionType.BLOCK_BREAK,"create:cart:test:mechanism=create:mechanical_drill@1,2,3",null),"recorded drill visible");
        check(hidden.hidden(ActionType.CHAT_MESSAGE,null,null),"exact hidden type visible");
        check(!hidden.hidden(ActionType.BLOCK_PLACE,"mod:create",null),"unrelated source hidden");
        var byMod = hidden.toggleMod("create");
        check(byMod.hidden(ActionType.BLOCK_PLACE, "mod:create", null), "legacy Create source visible");
        check(byMod.hidden(ActionType.BLOCK_PLACE, "create:cart:test:place", null), "native Create source visible");
        check(!byMod.hidden(ActionType.BLOCK_PLACE, "mod:create_cobblestone:place", null), "different mod accidentally hidden");
        check(byMod.toggleMod("create").equals(hidden), "restoring a mod lost other rules");
        String origin = GuiEventVisibility.UNATTRIBUTED;
        var system = GuiEventVisibility.ALL_VISIBLE.toggleSource(origin);
        check(system.hidden(ActionType.BLOCK_PLACE, origin, null), "system origin remained visible");
        check(!system.hidden(ActionType.BLOCK_PLACE, "player:place_block", null)
                && !system.hidden(ActionType.BLOCK_PLACE, "mod:create", null)
                && !system.hidden(ActionType.BLOCK_PLACE, origin + "_other", null), "system filter broadened to other origins");
        check(system.toggleSource(origin).equals(GuiEventVisibility.ALL_VISIBLE), "system origin could not be restored");
        check(GuiEventVisibility.systemOrigin("player:place_block").isEmpty(), "player source offered as system filter");
        check(!GuiEventVisibility.ALL_VISIBLE.toggle(30).active(),"queue policy became a visibility filter");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static void reject(Runnable action){try{action.run();throw new AssertionError("Invalid time accepted");}catch(IllegalArgumentException expected){}}
}
