package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import java.util.*;

final class PlayerBlockTransferTest {
    static void run() throws Exception {
        var row=row();var player=row.actorUuid;
        var first=state(7,false);var last=state(0,false);
        PlayerBlockTransfer.attach(row,new PlayerBlockTransfer.Capture(UUID.randomUUID(),player,row.dim,BlockPos.ZERO,
                "net.minecraft.world.inventory.ChestMenu",first,last,true,UUID.randomUUID()),slots(0,27),slots(7,27));
        var before=new RollbackEngine.ReplayCheckpoint(NbtSerde.fromSnbt(row.blockAfter),NbtSerde.fromSnbt(row.beBefore),NbtSerde.fromSnbt(row.containerSlotsBefore));
        var after=new RollbackEngine.ReplayCheckpoint(before.state(),NbtSerde.fromSnbt(row.beAfter),NbtSerde.fromSnbt(row.containerSlotsAfter));
        var proof=PlayerBlockTransfer.parse(row,1,before,after);
        check(proof!=null&&proof.player().equals(player)&&proof.before().equals(first)&&proof.after().equals(last),"exact player proof lost");
        PlayerBlockTransfer.verifyHistory(new History(List.of(row)),List.of(proof));
        PlayerBlockTransfer.verifyHistory(new History(List.of(row,row)),List.of(proof));
        var legacy=row.copyForQueue();legacy.playerInvBefore=legacy.playerInvBefore.replace("Format:2","Format:1");
        refused("incomplete",()->PlayerBlockTransfer.parse(legacy,1,before,after));
        var missingCoverage=row.copyForQueue();var envelope=NbtSerde.fromSnbt(missingCoverage.playerInvAfter);envelope.remove("Coverage");missingCoverage.playerInvAfter=envelope.toString();
        refused("incomplete",()->PlayerBlockTransfer.parse(missingCoverage,1,before,after));
        var changed=row.copyForQueue();changed.playerInvAfter=changed.playerInvAfter.replace("ChestMenu","HopperMenu");
        refused("receipt_changed",()->PlayerBlockTransfer.verifyHistory(new History(List.of(changed)),List.of(proof)));
        refused("receipt_missing",()->PlayerBlockTransfer.verifyHistory(new History(List.of()),List.of(proof)));
        var later=row.copyForQueue();later.id++;later.ts++;later.type=ActionType.BLOCK_BREAK;later.playerInvBefore=null;later.playerInvAfter=null;
        refused("later_history",()->PlayerBlockTransfer.verifyHistory(new History(List.of(row,later)),List.of(proof)));
        var gap=row.copyForQueue();gap.id+=2;gap.type=ActionType.CAPTURE_GAP;gap.dim="another:dimension";
        refused("capture_gap",()->PlayerBlockTransfer.verifyHistory(new History(List.of(row,gap)),List.of(proof)));
        var failed=row();PlayerBlockTransfer.attach(failed,new PlayerBlockTransfer.Capture(UUID.randomUUID(),failed.actorUuid,failed.dim,BlockPos.ZERO,
                "net.minecraft.world.inventory.ChestMenu",first,last,false,UUID.randomUUID()),slots(0,27),slots(7,27));
        refused("incomplete",()->PlayerBlockTransfer.parse(failed,1,before,after));
        var imbalance=row();PlayerBlockTransfer.attach(imbalance,new PlayerBlockTransfer.Capture(UUID.randomUUID(),imbalance.actorUuid,imbalance.dim,BlockPos.ZERO,
                "net.minecraft.world.inventory.ChestMenu",first,last,true,UUID.randomUUID()),slots(0,27),slots(8,27));
        refused("incomplete",()->PlayerBlockTransfer.parse(imbalance,1,before,after));
        var cursor=state(0,true);check(PlayerBlockTransfer.counted(cursor).getList("Items",10).getCompound(0).getInt("Slot")==41,"cursor omitted from count");
        PlayerBlockTransfer.attach(row,new PlayerBlockTransfer.Capture(UUID.randomUUID(),player,row.dim,BlockPos.ZERO,
                "net.minecraft.world.inventory.ChestMenu",cursor,last,true,UUID.randomUUID()),slots(0,27),slots(7,27));
        check(PlayerBlockTransfer.parse(row,1,before,after)!=null,"proven cursor transfer rejected");
        offlineSlots(player);
        System.out.println("PLAYER_BLOCK_TRANSFER_OK: exact inventory/cursor proof, balanced native transfer, exception/imbalance refusal, receipt hash, later history/gap refusal and offline main/armor/offhand conversion");
    }
    private static void offlineSlots(UUID player) throws Exception {
        var directory=java.nio.file.Files.createTempDirectory("recovery-player-data");var path=directory.resolve(player+".dat");
        try {
            var root=new CompoundTag();root.putUUID("UUID",player);root.putInt("DataVersion",1234);var items=new ListTag();
            for(int slot:new int[]{0,35,100,103,150}){var tag=item(1);tag.putByte("Slot",(byte)slot);items.add(tag);}
            root.put("Inventory",items);NbtIo.writeCompressed(root,path);
            var state=RecoveryPlayerDependencies.readOffline(directory,player,1024*1024,1234);
            var values=state.getCompound("Inventory").getList("Items",10).stream().map(raw->((CompoundTag)raw).getInt("Slot")).toList();
            check(values.equals(List.of(0,35,36,39,40))&&state.getCompound("Cursor").isEmpty(),"offline inventory mapping is wrong");
            items.add(items.getCompound(0).copy());NbtIo.writeCompressed(root,path);
            try{RecoveryPlayerDependencies.readOffline(directory,player,1024*1024,1234);throw new AssertionError("duplicate offline slot accepted");}
            catch(java.io.IOException expected){check(expected.getMessage().contains("duplicate_slot"),"wrong invalid slot reason");}
        } finally {java.nio.file.Files.deleteIfExists(path);java.nio.file.Files.deleteIfExists(directory);}
    }
    static CompoundTag item(int count){var item=new CompoundTag();item.putString("id","minecraft:diamond");item.putInt("count",count);return item;}
    static CompoundTag slots(int count,int size) {
        var inventory=new CompoundTag();inventory.putInt("Size",size);var items=new ListTag();
        if(count>0){var item=item(count);item.putInt("Slot",0);items.add(item);}inventory.put("Items",items);return inventory;
    }
    static CompoundTag state(int count,boolean cursor){var state=new CompoundTag();state.put("Inventory",slots(count,41));state.put("Cursor",cursor?item(7):new CompoundTag());return state;}
    static LogEntry row() {
        var row=new LogEntry();row.id=41;row.ts=1000;row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.actorUuid=UUID.randomUUID();row.actorName="TransferActor";
        row.source="player:container_click";row.dim="minecraft:overworld";row.blockAfter="{Name:'minecraft:barrel'}";
        row.beBefore="{id:'minecraft:barrel',Items:[]}";row.beAfter="{id:'minecraft:barrel',Items:[{Slot:0b,id:'minecraft:diamond',count:7}]}";
        row.containerSlotsBefore=slots(0,27).toString();row.containerSlotsAfter=slots(7,27).toString();return row;
    }
    static final class History implements LogStorage {
        private final List<LogEntry> rows;
        History(List<LogEntry> rows){this.rows=List.copyOf(rows);}
        public void append(LogEntry row){throw new UnsupportedOperationException();}
        public void shutdown(){}
        public List<LogEntry> queryReverse(LogQuery query){throw new UnsupportedOperationException();}
        public List<LogEntry> query(LogQuery q) {
            return rows.stream().filter(row->q.dim==null||q.dim.equals("*")||Objects.equals(q.dim,row.dim))
                    .filter(row->row.ts>=q.sinceTs&&row.ts<=q.untilTs)
                    .filter(row->q.exactIds==null||Arrays.stream(q.exactIds).anyMatch(id->id==row.id))
                    .filter(row->q.exactId==0||q.exactId==row.id).filter(row->q.type==null||q.type==row.type)
                    .filter(row->q.types==null||q.types.contains(row.type))
                    .filter(row->q.chronological?row.ts>q.cursorTs||row.ts==q.cursorTs&&row.id>q.afterId:row.id>q.afterId)
                    .filter(row->q.minPos==null||row.x>=q.minPos.getX()&&row.y>=q.minPos.getY()&&row.z>=q.minPos.getZ())
                    .filter(row->q.maxPos==null||row.x<=q.maxPos.getX()&&row.y<=q.maxPos.getY()&&row.z<=q.maxPos.getZ())
                    .sorted(q.chronological?Comparator.comparingLong((LogEntry row)->row.ts).thenComparingLong(row->row.id):Comparator.comparingLong(row->row.id))
                    .limit(q.limit).toList();
        }
    }
    private static void check(boolean okay,String message){if(!okay)throw new AssertionError(message);}
    private static void refused(String reason,Runnable action){try{action.run();throw new AssertionError("accepted: "+reason);}catch(IllegalStateException expected){check(expected.getMessage().contains(reason),"wrong refusal: "+expected);}}
}
