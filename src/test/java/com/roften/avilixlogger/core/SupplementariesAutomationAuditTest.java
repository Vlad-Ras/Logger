package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.supplementaries.SupplementariesAutomationAudit;
import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import net.minecraft.nbt.*;
import java.util.*;

public final class SupplementariesAutomationAuditTest {
    private static CompoundTag board(String id,String text){
        var root=SupplementariesTextAuditTest.text("board label",null);var items=new ListTag();var item=new CompoundTag();item.putByte("Slot",(byte)0);item.putString("id",id);item.putInt("count",1);
        var components=new CompoundTag();var book=new CompoundTag();book.putString("title","Native title");book.putString("author","Data Gatherer");book.putInt("generation",1);
        var pages=new ListTag();var page=new CompoundTag();page.putString("raw",text);page.putString("filtered","filtered "+text);pages.add(page);book.put("pages",pages);components.put("minecraft:written_book_content",book);item.put("components",components);items.add(item);root.put("Items",items);return root;
    }
    public static LogEntry record(){
        var row=AsyncPipelineTest.row("supplementaries_display;mechanism=create:display_link@60,104,45;dimension=minecraft:overworld;completed=false");row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.source=SupplementariesAutomationAudit.SOURCE;
        row.actorUuid=null;row.actorName="SYSTEM[create:display_link]";row.dim="minecraft:overworld";row.x=64;row.y=104;row.z=45;
        row.blockBefore="{Name:'supplementaries:notice_board',Properties:{has_book:'true'}}";row.blockAfter=row.blockBefore;
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,board("minecraft:written_book","До страницы"));row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,board("minecraft:written_book","После страницы"));row.materializeSnapshots();return row;
    }
    public static void run(){
        var before=board("minecraft:written_book","raw");var after=before.copy();after.getList("Items",Tag.TAG_COMPOUND).getCompound(0).getCompound("components").getCompound("minecraft:written_book_content").getList("pages",Tag.TAG_COMPOUND).getCompound(0).putString("filtered","new filter");
        check(SupplementariesAutomationAudit.changed(before,after),"book filtered-only change missing");
        check(SupplementariesAutomationAudit.changed(before,board("minecraft:writable_book","raw")),"native book conversion missing");
        after=before.copy();after.putInt("PageNumber",1);check(SupplementariesAutomationAudit.changed(before,after),"page index missing");
        after=before.copy();var reservation=new CompoundTag();reservation.putLong("Line1",42);after.put("NeoForgeData",reservation);check(!SupplementariesAutomationAudit.changed(before,after),"reservation-only metadata became text event");
        check(!SupplementariesAutomationAudit.changed(before,before.copy())&&!SupplementariesAutomationAudit.changed(before,null),"no-op/null accepted");
        var row=record();var copy=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(copy.actorUuid==null&&copy.actorName.equals(row.actorName)&&copy.source.equals(row.source)&&copy.x==64&&copy.extra.contains("@60,104,45")&&copy.beBefore.equals(row.beBefore)&&copy.beAfter.equals(row.beAfter),"automation journal lost actor/positions/full book");
        int mask=CaptureTypePolicy.mask();try{for(int group:new int[]{1,2}){CaptureTypePolicy.install(CaptureTypePolicy.changed(0,group,false));check(SupplementariesAutomationAudit.enter(0,null)==null&&SupplementariesAutomationAudit.current()==null,"disabled display capture retained frame or dereferenced native arguments");}}finally{CaptureTypePolicy.install(mask);}
        row.beBefore="{";check(SupplementariesTextAudit.describe(null,row).isEmpty(),"malformed automation payload broke detail fallback");
        System.out.println("SUPPLEMENTARIES_DISPLAY_EVIDENCE_OK: book conversion/filter/pages/no-op, reservation-only separation, system actor + target/mechanism, full journal, disabled capture and malformed details");
    }
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
}
