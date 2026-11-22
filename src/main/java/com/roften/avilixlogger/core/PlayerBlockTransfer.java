package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.*;
import net.minecraft.world.level.block.entity.*;

import java.util.*;

/** Exact evidence of one native, synchronous container/player/cursor exchange. */
public final class PlayerBlockTransfer {
    public static final String KIND="player_block_transfer";
    public record Capture(UUID transfer,UUID player,String dimension,BlockPos pos,String menu,
                          CompoundTag before,CompoundTag after,boolean success) {}
    public record Proof(long event,long timestamp,UUID transfer,UUID player,String dimension,BlockPos pos,int afterIndex,
                        CompoundTag before,CompoundTag after,long cutoffId,String fingerprint) {
        Proof cutoff(long id){return new Proof(event,timestamp,transfer,player,dimension,pos,afterIndex,before,after,id,fingerprint);}
    }
    private static final Set<Class<?>> MENUS=Set.of(ChestMenu.class,HopperMenu.class,DispenserMenu.class,ShulkerBoxMenu.class);
    private static final Set<Class<?>> BLOCKS=Set.of(ChestBlockEntity.class,TrappedChestBlockEntity.class,BarrelBlockEntity.class,
            HopperBlockEntity.class,DispenserBlockEntity.class,DropperBlockEntity.class,ShulkerBoxBlockEntity.class);
    private PlayerBlockTransfer() {}

    public static boolean eligible(ServerLevel level,BlockPos pos,AbstractContainerMenu menu,ServerPlayer player) {
        var be=level.getBlockEntity(pos);
        return be!=null&&BLOCKS.contains(be.getClass())&&MENUS.contains(menu.getClass())
                &&!(player instanceof net.neoforged.neoforge.common.util.FakePlayer)&&player.getInventory().getContainerSize()==41;
    }
    public static CompoundTag snapshot(ServerPlayer player,AbstractContainerMenu menu) {
        var inventory=EntityContainerSerde.snapshot(player.getInventory(),player.registryAccess());
        if(inventory==null||inventory.getInt("Size")!=41)throw new IllegalStateException("player_inventory_snapshot_failed");
        var cursor=menu.getCarried().isEmpty()?new CompoundTag():NbtSerde.snapshotItemStack(menu.getCarried(),player.registryAccess());
        if(cursor==null)throw new IllegalStateException("player_cursor_snapshot_failed");
        var state=new CompoundTag();state.put("Inventory",inventory);state.put("Cursor",cursor);return state;
    }
    /** No world/player/menu references enter this worker method. Failed evidence is retained as incomplete. */
    public static void attach(LogEntry row,Capture capture,CompoundTag blockBefore,CompoundTag blockAfter) {
        if(capture==null)return;
        boolean complete=capture.success()&&Objects.equals(row.actorUuid,capture.player())
                &&capture.before()!=null&&capture.after()!=null&&singleStorage(blockBefore,capture)&&singleStorage(blockAfter,capture);
        if(complete)try {
            RecoveryItemBalance.verify(List.of(new RecoveryItemBalance.Input(capture.dimension(),capture.pos(),
                            new RollbackEngine.ReplayCheckpoint(new CompoundTag(),null,blockBefore),
                            new RollbackEngine.ReplayCheckpoint(new CompoundTag(),null,blockAfter))),
                    List.of(new RecoveryItemBalance.Change(counted(capture.before()),counted(capture.after()))),
                    Math.max(1,PayloadSizeEstimator.estimateTags(blockBefore,blockAfter,capture.before(),capture.after()))*8L+65536,()->false);
        } catch(RuntimeException invalid) {complete=false;}
        row.playerInvBefore=envelope(capture,capture.before(),complete).toString();
        row.playerInvAfter=envelope(capture,capture.after(),complete).toString();
    }
    private static boolean singleStorage(CompoundTag slots,Capture capture) {
        if(!ContainerSlotSnapshot.isValid(slots))return false;
        if(!slots.contains("SlotSources"))return true;
        var sources=slots.getList("SlotSources",Tag.TAG_COMPOUND);
        if(sources.size()!=1)return false;
        var source=sources.getCompound(0);
        return source.getInt("FirstSlot")==0&&source.getInt("SlotCount")==slots.getInt("Size")
                &&source.getString("Dimension").equals(capture.dimension())
                &&new BlockPos(source.getInt("X"),source.getInt("Y"),source.getInt("Z")).equals(capture.pos());
    }
    private static CompoundTag envelope(Capture capture,CompoundTag state,boolean complete) {
        var tag=new CompoundTag();tag.putInt("Format",1);tag.putString("Kind",KIND);tag.putUUID("Transfer",capture.transfer());
        tag.putUUID("Player",capture.player());tag.putString("Dimension",capture.dimension());tag.putLong("Pos",capture.pos().asLong());
        tag.putString("Menu",capture.menu());tag.putBoolean("Complete",complete);if(state!=null)tag.put("State",state);return tag;
    }
    public static boolean encoded(LogEntry row) {
        return row.type==ActionType.BLOCK_ENTITY_NBT_CHANGE&&(row.playerInvBefore!=null&&row.playerInvBefore.contains(KIND)
                ||row.playerInvAfter!=null&&row.playerInvAfter.contains(KIND));
    }
    public static Proof parse(LogEntry row,int afterIndex,RollbackEngine.ReplayCheckpoint blockBefore,RollbackEngine.ReplayCheckpoint blockAfter) {
        if(!encoded(row))return null;
        try {
            var before=NbtSerde.fromSnbt(row.playerInvBefore);var after=NbtSerde.fromSnbt(row.playerInvAfter);
            if(row.id<=0||before==null||after==null||afterIndex<=0)throw new IllegalStateException("replay_player_transfer_invalid");
            for(var tag:List.of(before,after)) {
                if(tag.getInt("Format")!=1||!tag.getString("Kind").equals(KIND)||!tag.hasUUID("Transfer")||!tag.hasUUID("Player")
                        ||!tag.getBoolean("Complete")||!tag.contains("Pos",Tag.TAG_LONG)||!tag.contains("State",Tag.TAG_COMPOUND)
                        ||!Objects.equals(row.actorUuid,tag.getUUID("Player"))||!Objects.equals(row.dim,tag.getString("Dimension"))
                        ||!BlockPos.of(tag.getLong("Pos")).equals(new BlockPos(row.x,row.y,row.z)))
                    throw new IllegalStateException("replay_player_transfer_incomplete");
                counted(tag.getCompound("State"));
            }
            if(!before.getUUID("Transfer").equals(after.getUUID("Transfer")))throw new IllegalStateException("replay_player_transfer_invalid");
            if(!before.getString("Menu").equals(after.getString("Menu"))||MENUS.stream().noneMatch(type->type.getName().equals(after.getString("Menu"))))throw new IllegalStateException("replay_player_transfer_unsupported_menu");
            if(!Set.of("player:container_click","player:menu_button").contains(row.source))throw new IllegalStateException("replay_player_transfer_invalid");
            var capture=new Capture(after.getUUID("Transfer"),after.getUUID("Player"),row.dim,new BlockPos(row.x,row.y,row.z),after.getString("Menu"),
                    before.getCompound("State"),after.getCompound("State"),true);
            if(!singleStorage(blockBefore.slots(),capture)||!singleStorage(blockAfter.slots(),capture))throw new IllegalStateException("replay_player_transfer_incomplete");
            RecoveryItemBalance.verify(List.of(new RecoveryItemBalance.Input(row.dim,capture.pos(),blockBefore,blockAfter)),
                    List.of(new RecoveryItemBalance.Change(counted(capture.before()),counted(capture.after()))),
                    Math.max(1,PayloadSizeEstimator.estimateTags(blockBefore.slots(),blockAfter.slots(),capture.before(),capture.after()))*8L+65536,()->false);
            return new Proof(row.id,row.ts,capture.transfer(),capture.player(),row.dim,capture.pos(),afterIndex,capture.before(),capture.after(),row.id,fingerprint(row));
        } catch(RuntimeException invalid) {
            throw new IllegalStateException(invalid.getMessage()+"; запись #"+row.id+"; "+row.dim+" "+new BlockPos(row.x,row.y,row.z),invalid);
        }
    }
    private static String fingerprint(LogEntry row) {
        try {
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            for(var field:Arrays.asList(Long.toString(row.id),Long.toString(row.ts),row.dim,row.type==null?null:row.type.name(),
                    String.valueOf(row.actorUuid),row.actorName,row.source,row.x+","+row.y+","+row.z,row.blockBefore,row.blockAfter,
                    row.beBefore,row.beAfter,row.containerSlotsBefore,row.containerSlotsAfter,row.playerInvBefore,row.playerInvAfter)) {
                var bytes=field==null?new byte[0]:field.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(8).putInt(field==null?-1:bytes.length).putInt(bytes.length).array());digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static void verifyHistory(LogStorage storage,List<Proof> proofs) {
        if(proofs.isEmpty())return;
        try {if(!storage.awaitVisible(System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30)))throw new IllegalStateException("Журнал переносов ещё не сохранён полностью");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.util.concurrent.CancellationException();}
        var expected=new HashMap<Long,Proof>();for(var proof:proofs)expected.put(proof.event(),proof);
        var ids=new ArrayList<>(expected.keySet());
        for(int offset=0;offset<ids.size();offset+=1000) {
            var query=new LogQuery();query.dim="*";query.untilTs=Long.MAX_VALUE;query.requireDetails=true;query.limit=2000;
            query.exactIds=ids.subList(offset,Math.min(ids.size(),offset+1000)).stream().mapToLong(Long::longValue).toArray();query.debugSource="player_transfer_receipts";
            var wanted=new HashSet<Long>();for(long id:query.exactIds)wanted.add(id);
            for(var row:storage.query(query)) {
                var proof=expected.get(row.id);
                if(proof==null||!fingerprint(row).equals(proof.fingerprint()))throw new IllegalStateException("replay_player_transfer_receipt_changed: #"+row.id);
                wanted.remove(row.id);
            }
            if(!wanted.isEmpty())throw new IllegalStateException("replay_player_transfer_receipt_missing: "+wanted.iterator().next());
        }
        var gap=new LogQuery();gap.dim="*";gap.untilTs=Long.MAX_VALUE;gap.types=EnumSet.of(ActionType.CAPTURE_GAP);gap.limit=1;
        gap.afterId=proofs.stream().mapToLong(Proof::event).min().orElseThrow();gap.requireDetails=true;gap.debugSource="player_transfer_capture_gaps";
        if(!storage.query(gap).isEmpty())throw new IllegalStateException("replay_player_transfer_capture_gap: после переноса есть незаписанный интервал");
        var dimensions=new HashMap<String,Map<BlockPos,Long>>();
        for(var proof:proofs)dimensions.computeIfAbsent(proof.dimension(),ignored->new HashMap<>()).merge(proof.pos(),proof.cutoffId(),Math::max);
        int examined=0;
        for(var dimension:dimensions.entrySet()) {
            var query=new LogQuery();query.dim=dimension.getKey();query.untilTs=Long.MAX_VALUE;query.limit=1000;query.requireDetails=true;
            query.afterId=dimension.getValue().values().stream().mapToLong(Long::longValue).min().orElseThrow();
            int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
            for(var pos:dimension.getValue().keySet()){minX=Math.min(minX,pos.getX());minY=Math.min(minY,pos.getY());minZ=Math.min(minZ,pos.getZ());maxX=Math.max(maxX,pos.getX());maxY=Math.max(maxY,pos.getY());maxZ=Math.max(maxZ,pos.getZ());}
            query.minPos=new BlockPos(minX,minY,minZ);query.maxPos=new BlockPos(maxX,maxY,maxZ);
            query.types=EnumSet.of(ActionType.BLOCK_PLACE,ActionType.BLOCK_BREAK,ActionType.BLOCK_ENTITY_NBT_CHANGE,ActionType.CONTAINER_PUT,ActionType.CONTAINER_TAKE);
            query.debugSource="player_transfer_later_blocks";
            while(true) {
                var rows=storage.query(query);if(rows.isEmpty())break;long next=query.afterId;
                for(var row:rows) {
                    if(++examined>100_000)throw new IllegalStateException("Слишком много более поздних событий для проверки переносов");
                    if(row.id<=query.afterId)throw new IllegalStateException("Хранилище не продвинуло проверку переносов");
                    next=Math.max(next,row.id);var cutoff=dimension.getValue().get(new BlockPos(row.x,row.y,row.z));
                    if(!Objects.equals(row.dim,query.dim)||row.type==null||!query.types.contains(row.type))throw new IllegalStateException("Хранилище нарушило фильтр проверки переносов");
                    if(cutoff!=null&&row.id>cutoff)throw new IllegalStateException("replay_player_transfer_later_history: "+row.dim+" "+new BlockPos(row.x,row.y,row.z)+"; запись #"+row.id);
                }
                if(rows.size()<query.limit)break;query.afterId=next;
            }
        }
    }
    /** Includes the carried stack as slot 41; it is never silently excluded from conservation. */
    public static CompoundTag counted(CompoundTag state) {
        if(state==null||!state.contains("Inventory",Tag.TAG_COMPOUND)||!state.contains("Cursor",Tag.TAG_COMPOUND))
            throw new IllegalStateException("replay_player_transfer_incomplete");
        var inventory=state.getCompound("Inventory");
        if(!ContainerSlotSnapshot.isValid(inventory)||inventory.getInt("Size")!=41||inventory.contains("SlotSources"))
            throw new IllegalStateException("replay_player_transfer_incomplete");
        var result=inventory.copy();result.putInt("Size",42);
        var cursor=state.getCompound("Cursor");
        if(!cursor.isEmpty()){var item=cursor.copy();item.putInt("Slot",41);result.getList("Items",Tag.TAG_COMPOUND).add(item);}
        return result;
    }
}
