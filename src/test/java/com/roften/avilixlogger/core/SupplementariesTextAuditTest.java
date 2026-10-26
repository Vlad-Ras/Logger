package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.supplementaries.SupplementariesTextAudit;
import net.minecraft.nbt.*;

public final class SupplementariesTextAuditTest {
    public static CompoundTag text(String raw,String filtered){var root=new CompoundTag();var holder=new CompoundTag();var messages=new ListTag();messages.add(StringTag.valueOf(raw));holder.put("message",messages);if(filtered!=null){var f=new ListTag();f.add(StringTag.valueOf(filtered));holder.put("filtered_message",f);}root.put("TextHolder",holder);return root;}
    public static CompoundTag signs(String upper,String lower){var root=new CompoundTag();root.put("SignUp",text(upper,null));root.put("SignDown",text(lower,null));return root;}
    public static LogEntry record(){var row=AsyncPipelineTest.row("supplementaries_text_edit;completed=true");row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.source="mod:supplementaries:text_edit";row.dim="minecraft:overworld";row.x=-37;row.y=103;row.z=61;row.actorUuid=java.util.UUID.randomUUID();row.actorName="NativeTextEditor";row.blockBefore="{Name:'supplementaries:sign_post'}";row.blockAfter=row.blockBefore;row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,signs("До верхний","До нижний"));row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,signs("После верхний","После нижний"));row.materializeSnapshots();return row;}
    public static void run(){
        check(SupplementariesTextAudit.changed(text("До",null),text("После",null)),"root TextHolder edit lost");
        check(SupplementariesTextAudit.changed(signs("До","Низ"),signs("После","Низ"))&&SupplementariesTextAudit.changed(signs("Верх","До"),signs("Верх","После")),"one of two signpost holders ignored");
        check(SupplementariesTextAudit.changed(text("raw","old mask"),text("raw","new mask")),"filtered-only edit ignored");
        check(!SupplementariesTextAudit.changed(text("same",null),text("same","same")),"redundant native filtered representation fabricated an edit");
        var before=signs("same","same");var after=before.copy();after.putBoolean("Waxed",true);after.getCompound("SignUp").putFloat("Yaw",90);after.getCompound("SignDown").getCompound("TextHolder").putString("color","red");
        check(!SupplementariesTextAudit.changed(before,after),"wax/rotation/color became text edit");
        check(!SupplementariesTextAudit.changed(new CompoundTag(),text("new",null))&&!SupplementariesTextAudit.changed(signs("a","b"),text("c",null))&&!SupplementariesTextAudit.changed(null,after),"unknown/missing provider schema accepted");
        var row=record();var saved=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(row.actorUuid.equals(saved.actorUuid)&&saved.actorName.equals("NativeTextEditor")&&saved.x==-37&&saved.y==103&&saved.z==61&&row.beBefore.equals(saved.beBefore)&&row.beAfter.equals(saved.beAfter),"text editor/position/both holders lost in journal");
        int mask=CaptureTypePolicy.mask();try{for(int group:new int[]{1,2}){CaptureTypePolicy.install(CaptureTypePolicy.changed(0,group,false));check(SupplementariesTextAudit.begin(null,null)==null&&!SupplementariesTextAudit.active(),"disabled text capture touched native player/position or leaked scope");check(!SignTextAudit.enabled(),"vanilla sign capture ignored a disabled preset");SignTextAudit.after(null,new CompoundTag());check(!CaptureTypePolicy.allows(row.type,row.source,row.extra),"text capture bypassed disabled preset");}}finally{CaptureTypePolicy.install(mask);}
        var corrupt=record();corrupt.beBefore="{";check(SupplementariesTextAudit.describe(null,corrupt).isEmpty(),"corrupt text broke detail fallback");
        System.out.println("SUPPLEMENTARIES_TEXT_EVIDENCE_OK: root/two holders, raw/filtered-only, no-op/style separation, schema refusal, editor/position/journal, both disabled presets and corrupt fallback");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
