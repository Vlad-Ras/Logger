package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.util.*;

final class StorageUiEvidenceTest {
    static void run() {
        var row=new LogEntry();row.id=42;row.dim="minecraft:the_nether";row.type=ActionType.CLAIM_ACTION;row.source="mod:openpartiesandclaims:claim-state";
        var before=new CompoundTag();before.putBoolean("Claimed",false);
        var after=new CompoundTag();after.putBoolean("Claimed",true);after.putInt("ChunkX",-2);after.putInt("ChunkZ",3);after.putString("OwnerUUID","62000000-0000-0000-0000-000000000001");
        row.beBefore=before.toString();row.beAfter=after.toString();
        var position=EntryPositionEvidence.read(row);
        check(position!=null&&position.chunk()&&position.x()==-24&&position.z()==56&&position.dimension().equals(row.dim),"negative chunk center or dimension lost");
        check(ModAuditDetails.claimSummary(before,after).startsWith("заприватил чанк"),"claim direction lost");
        check(ModAuditDetails.claimSummary(after,before).startsWith("расприватил чанк"),"unclaim direction lost");
        var lines=ModAuditDetails.describe(row);
        check(lines.stream().anyMatch(line->line.getString().contains("игрок не установлен")),"owner was presented as initiating actor");
        row.type=ActionType.PARTY_CHANGE;row.beAfter="{}";check(EntryPositionEvidence.read(row)==null,"global party state teleported to zero");
        row.type=ActionType.MACHINE_STATE_CHANGE;check(EntryPositionEvidence.read(row)==null,"global machine state teleported to zero");
        var address=new CompoundTag();address.putInt("X",0);address.putInt("Y",64);address.putInt("Z",0);row.y=64;row.beAfter=address.toString();
        check(EntryPositionEvidence.read(row)!=null,"legitimate origin coordinates rejected");
        address.putBoolean("PositionKnown",false);row.beAfter=address.toString();check(EntryPositionEvidence.read(row)==null,"explicitly unknown position accepted");
        row.type=ActionType.BLOCK_PLACE;row.beAfter=null;check(EntryPositionEvidence.read(row)!=null,"historical block address rejected");
        var slot=new CompoundTag();slot.putInt("Slot",30);slot.putInt("LocalSlot",3);slot.putBoolean("PhysicalAddressKnown",true);slot.putInt("BeforeCount",4);slot.putInt("AfterCount",2);
        var state=new CompoundTag();state.putString("Name","minecraft:chest");address.remove("PositionKnown");address.put("BlockState",state);slot.put("Address",address);
        after=new CompoundTag();after.put("StorageSlot",slot);row.beAfter=after.toString();row.extra="container slot 30";row.source="player:menu";row.type=ActionType.CONTAINER_TAKE;
        lines=ModAuditDetails.describe(row);
        check(lines.stream().anyMatch(line->line.getString().contains("4 → 2"))&&lines.stream().anyMatch(line->line.getString().contains("minecraft:chest")),"physical slot details lost amount/block");
        var operation=new CompoundTag();operation.putString("Direction","insert");operation.putLong("TransferredMB",500);operation.putLong("PlayerExperienceDelta",-500);
        check(ModAuditDetails.storageSummary(operation).contains("добавил в хранилище 500 mB")&&ModAuditDetails.storageSummary(operation).contains("-500"),"hatch direction or XP sign lost");
        row.extra="ui="+ModAuditDetails.storageSummary(operation)+"|audit|provider=internal; operation=internal";
        check(!ModAuditDetails.feedSummary(row).contains("provider=")&&ModAuditDetails.feedSummary(row).contains("500 mB"),"main journal exposed internal audit metadata instead of a readable result");
        System.out.println("STORAGE_UI_EVIDENCE_OK: exact slot amounts, neutral actor, claim direction, negative chunk and refusal of unknown positions");
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
}
