package com.roften.avilixlogger.core;
import net.minecraft.nbt.CompoundTag;
import java.util.*;
/** Exact receipts through real storage, minimal detail policy and connection reopen. */
final class ReleaseStorageEvidence {
    private static final UUID ACTOR=UUID.fromString("62000000-0000-0000-0000-000000000001");
    static List<LogEntry> rows(){
        var diagnostic=base(ActionType.LOGGER_DIAGNOSTIC,"logger:diagnostic");diagnostic.dim="*";diagnostic.beAfter="{Kind:'LoggerPipelineCheck',PositionKnown:false}";
        long now=System.currentTimeMillis();
        return List.of(slot(false),slot(true),hatch(false),hatch(true),claim(false),claim(true),LoggerRuntime.captureGapEntry(new ServerCaptureGuard.Gap(now-1000,now,"events","FULL",2,19)),diagnostic,
            paired(ActionType.ENTITY_EQUIPMENT_CHANGE,"mod:accessories:test:pair"),paired(ActionType.PARTY_CHANGE,"mod:ftbteams:test:pair"),
            paired(ActionType.ITEM_TRANSFORM,"mod:create_enchantment_industry:test:pair"),paired(ActionType.PLAYER_DEATH,"mod:scguns:test:pair"));
    }
    private static LogEntry base(ActionType type,String source){var e=AsyncPipelineTest.row("release-storage-evidence");e.type=type;e.source=source;e.dim="minecraft:overworld";e.x=-24;e.y=80;e.z=56;e.actorUuid=ACTOR;e.actorName="StorageReleaseActor";return e;}
    private static LogEntry slot(boolean insert){
        var e=base(insert?ActionType.CONTAINER_PUT:ActionType.CONTAINER_TAKE,"player:menu");e.count=insert?4:2;e.blockBefore=e.blockAfter="minecraft:chest";e.itemStackNbt="{id:'minecraft:diamond',count:"+e.count+"}";
        int before=insert?1:4,after=insert?5:2;e.extra="container slot 30; before="+before+"; after="+after;
        var slot=new CompoundTag();slot.putInt("Slot",30);slot.putInt("LocalSlot",3);slot.putBoolean("PhysicalAddressKnown",true);slot.putInt("BeforeCount",before);slot.putInt("AfterCount",after);
        var address=new CompoundTag();address.putInt("X",e.x);address.putInt("Y",e.y);address.putInt("Z",e.z);var block=new CompoundTag();block.putString("Name","minecraft:chest");address.put("BlockState",block);slot.put("Address",address);
        var item=new CompoundTag();item.putString("id","minecraft:diamond");item.putInt("count",before);slot.put("Before",item);item=item.copy();item.putInt("count",after);slot.put("After",item);
        var tag=new CompoundTag();tag.put("StorageSlot",slot);e.beAfter=tag.toString();return e;
    }
    private static LogEntry hatch(boolean insert){
        var e=base(ActionType.EXPERIENCE_CHANGE,"mod:create_enchantment_industry:experience-hatch");e.blockBefore=e.blockAfter="create:fluid_tank";
        var before=new CompoundTag();before.putString("Block",e.blockAfter);before.putInt("X",e.x);before.putInt("Y",e.y);before.putInt("Z",e.z);
        var xp=new CompoundTag();xp.putLong("Points",insert?40:20);before.put("PlayerExperience",xp);
        var contents=new CompoundTag();contents.putLong("AmountMB",insert?0:500);before.put("StorageContents",contents);
        var after=before.copy();xp=xp.copy();xp.putLong("Points",insert?20:40);after.put("PlayerExperience",xp);contents=contents.copy();contents.putLong("AmountMB",insert?500:0);after.put("StorageContents",contents);
        var operation=new CompoundTag();operation.putString("Direction",insert?"insert":"extract");operation.putLong("TransferredMB",500);operation.putLong("PlayerExperienceDelta",insert?-20:20);operation.putInt("HatchX",e.x+1);operation.putInt("HatchY",e.y);operation.putInt("HatchZ",e.z);after.put("StorageOperation",operation);
        e.beBefore=before.toString();e.beAfter=after.toString();e.extra="ui="+ModAuditDetails.storageSummary(operation)+"|audit|provider=test";return e;
    }
    private static LogEntry claim(boolean remove){
        var e=base(ActionType.CLAIM_ACTION,"mod:openpartiesandclaims:claim-state");var before=new CompoundTag();before.putInt("ChunkX",-2);before.putInt("ChunkZ",3);before.putBoolean("Claimed",remove);if(remove)before.putString("OwnerUUID",ACTOR.toString());
        var after=before.copy();after.putBoolean("Claimed",!remove);if(remove)after.remove("OwnerUUID");else after.putString("OwnerUUID",ACTOR.toString());
        if(remove){e.actorUuid=null;e.actorName="Сервер / механизм";}e.beBefore=before.toString();e.beAfter=after.toString();e.extra="ui="+ModAuditDetails.claimSummary(before,after)+"|audit|provider=test";return e;
    }
    private static LogEntry paired(ActionType type,String source){
        var e=base(type,source);var before=new CompoundTag();before.putString("Value","old");before.putBoolean("PositionKnown",false);
        var after=before.copy();after.putString("Value","new");var changes=new net.minecraft.nbt.ListTag();var change=new CompoundTag();change.putString("Path","Value");change.putString("Before","old");change.putString("After","new");changes.add(change);after.put("Changes",changes);
        e.beBefore=before.toString();e.beAfter=after.toString();return e;
    }
    static void verify(LogStorage db,List<LogEntry> rows){
        for(var expected:rows){
            var q=new LogQuery();q.exactId=expected.id;q.untilTs=Long.MAX_VALUE;q.requireDetails=true;q.limit=2;q.sourcePrefix=expected.source;
            var found=db.query(q);check(found.size()==1,"missing/duplicated receipt "+expected.type);var read=found.getFirst();
            check(read.type==expected.type&&Objects.equals(read.beBefore,expected.beBefore)&&Objects.equals(read.beAfter,expected.beAfter)&&Objects.equals(read.itemStackNbt,expected.itemStackNbt)&&Objects.equals(read.actorUuid,expected.actorUuid)&&Objects.equals(read.actorName,expected.actorName)&&read.count==expected.count&&Objects.equals(read.extra,expected.extra)&&Objects.equals(read.dim,expected.dim)&&read.x==expected.x&&read.y==expected.y&&read.z==expected.z,"receipt payload/actor/address changed "+expected.type);
            check(Objects.equals(EntryPositionEvidence.read(read),EntryPositionEvidence.read(expected)),"teleport evidence changed");check(!ModAuditDetails.describe(read).isEmpty(),"missing GUI details");
            q.requireDetails=false;found=db.query(q);check(found.size()==1&&found.getFirst().id==expected.id&&found.getFirst().type==expected.type,"missing GUI feed receipt");
            q.sourcePrefix=expected.source+":no-such-source";check(db.query(q).isEmpty(),"source filter broadened exact ID");
        }
        System.out.println("STORAGE_RELEASE_CLICKHOUSE_OK: slot deltas/physical address, hatch directions/XP, claim/unclaim actor/owner, gap/diagnostic and GUI feed/details");
    }
    private static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
}
