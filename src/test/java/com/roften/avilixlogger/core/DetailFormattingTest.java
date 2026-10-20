package com.roften.avilixlogger.core;

import net.minecraft.nbt.CompoundTag;
import java.util.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.check;

final class DetailFormattingTest {
    static void run(){
        var hit=new LogEntry();hit.type=ActionType.ENTITY_ATTACK;hit.actorName="Roften";hit.actorUuid=UUID.randomUUID();hit.entityType="minecraft:player";hit.entityUuid=UUID.randomUUID();hit.entityName="MaxKuz";
        for(var type:List.of(ActionType.ENTITY_ATTACK,ActionType.ENTITY_INTERACT,ActionType.ENTITY_MOUNT,ActionType.ENTITY_DISMOUNT,ActionType.ENTITY_CONTAINER_OPEN,ActionType.PROJECTILE_HIT,ActionType.ENTITY_DEATH)){
            hit.type=type;check(EntityLogIdentity.displayName(hit).equals("MaxKuz"),"target nickname absent for "+type);
        }
        var queued=hit.copyForQueue();hit.entityName="RenamedLater";check("MaxKuz".equals(queued.entityName),"target identity changed after queue handoff");
        var recovered=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(queued),LogEntry.class);check("MaxKuz".equals(recovered.entityName),"journal target nickname lost");
        queued.extra="{\"trainName\":\"a \\\"quoted\\\" train\"}";var stored=queued.copyForQueue();stored.extra=EntityLogIdentity.storageExtra(queued,queued.extra);stored.entityName=null;stored.entityUuid=null;
        EntityLogIdentity.restoreExtra(stored);check("MaxKuz".equals(stored.entityName)&&queued.entityUuid.equals(stored.entityUuid)&&queued.extra.equals(stored.extra),"target envelope corrupted structured extra/identity");
        var old=new LogEntry();old.entityType="minecraft:player";old.entityUuid=UUID.randomUUID();old.actorName="Roften";
        check(!EntityLogIdentity.displayName(old).contains("Roften")&&EntityLogIdentity.displayName(old).contains(old.entityUuid.toString()),"historical victim guessed from attacker");
        var custom=new CompoundTag();custom.putString("CustomName","{\"text\":\"Merchant\",\"extra\":[{\"text\":\" One\"}]}");old.entityNbt=custom.toString();old.entityType="minecraft:villager";
        check(EntityLogIdentity.displayName(old).equals("Merchant One"),"legacy named NPC lost");
        var mismatch=queued.copyForQueue();mismatch.extra=EntityLogIdentity.storageExtra(queued,queued.extra);mismatch.entityName=null;mismatch.entityUuid=UUID.randomUUID();EntityLogIdentity.restoreExtra(mismatch);
        check(mismatch.entityName==null,"foreign target metadata replaced UUID");
        var block=new LogEntry();block.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;
        block.blockBefore="{Name:'minecraft:lever',Properties:{powered:'false'}}";block.blockAfter="{Name:'minecraft:lever',Properties:{powered:'true'}}";
        block.beBefore="{id:'test:device',x:1,Settings:{Mode:'idle',Count:4},Items:[{Slot:2,Count:5b}],OldFlag:1b,Vector:[I;1,2]}";
        block.beAfter="{id:'test:device',x:99,Settings:{Mode:'work',Count:7},Items:[{Slot:2,Count:3b}],NewFlag:1b,Vector:[I;1,8]}";
        var changes=BlockChangeDetails.changes(block);String detail=String.join("\n",changes);
        check(detail.contains("powered: false → true")&&detail.contains("Settings.Mode: idle → work")&&detail.contains("Settings.Count: 4 → 7"),"specific properties/settings absent");
        check(detail.contains("Items[0].Count: 5b → 3b")&&detail.contains("OldFlag: 1b → отсутствует")&&detail.contains("NewFlag: отсутствует → 1b")&&detail.contains("Vector[1]: 2 → 8"),"nested/add/remove/array delta absent");
        check(!detail.contains("Настройки.x"),"technical coordinate noise rendered as a setting");
        block.blockAfter=block.blockBefore;block.beAfter=block.beBefore;check(BlockChangeDetails.changes(block).isEmpty(),"unchanged snapshots invented changes");
        block.beAfter="{Mode:1}";block.beBefore=null;check(BlockChangeDetails.changes(block).isEmpty(),"missing before snapshot treated as empty");
        var many=new CompoundTag();for(int i=0;i<1000;i++)many.putInt("setting"+i,i);block.beBefore="{}";block.beAfter=many.toString();
        var bounded=BlockChangeDetails.changes(block);check(bounded.size()<100&&bounded.getLast().contains("часть"),"large modded settings filled unbounded GUI");
        block.type=null;check(BlockChangeDetails.changes(block).isEmpty(),"unknown historical action broke details");
        System.out.println("DETAIL_FORMATTING_OK: captured targets, legacy fallback, journal identity, properties/settings/nested deltas and bounded output");
    }
}
