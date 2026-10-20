package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

/** Event-bound inventory snapshots around a tagged drop's native pickup, never a tick scan. */
public final class CartLootTransferAudit {
    private static final ThreadLocal<Attempt> CURRENT=new ThreadLocal<>();
    private static final String MIXED=CreateEntityOrigin.MIXED_DROP;
    private static final String MERGES="AvilixCreateMergeChain";
    private static final String MERGE_OVERFLOW="AvilixCreateMergeOverflow";
    private CartLootTransferAudit(){}
    private static String source(ItemEntity drop){return source(drop,"loot_pickup");}
    private static String source(ItemEntity drop,String phase){
        var data=drop instanceof CreateOriginDataAccess access?access.avilixlogger$originData():null;
        if(data==null)return null;
        if(data.contains(MIXED))return null;
        if(data.hasUUID("AvilixCartDrop"))return CartAuditContext.prefix(data.getUUID("AvilixCartDrop"))+phase+":0";
        String origin=data.getString("AvilixCreateDropSource");var id=CreateContraptionKind.id(origin);
        return id==null?null:CreateContraptionKind.fromSource(origin).source(id,phase)+":0";
    }
    private static List<String> origins(ItemEntity drop){
        var data=drop instanceof CreateOriginDataAccess access?access.avilixlogger$originData():null;
        if(data!=null&&data.contains(MIXED)){
            var list=data.getList(MIXED,8);var values=new LinkedHashSet<String>();
            for(int i=0;i<list.size();i++){
                String origin=list.getString(i);
                if(origin.length()<=512&&CreateContraptionKind.id(origin)!=null)values.add(origin);
            }
            return List.copyOf(values);
        }
        var direct=source(drop,"actor_result");return direct==null?List.of():List.of(direct);
    }
    private static boolean mixed(ItemEntity drop){
        var data=drop instanceof CreateOriginDataAccess access?access.avilixlogger$originData():null;
        return data!=null&&data.contains(MIXED);
    }
    private static void markMixed(ItemEntity drop,List<String> origins,ListTag chain,boolean overflow){
        var data=drop.getPersistentData();var list=new ListTag();
        for(String origin:origins)list.add(StringTag.valueOf(origin));
        data.put(MIXED,list);data.put(MERGES,chain.copy());
        if(overflow)data.putBoolean(MERGE_OVERFLOW,true);
        data.remove("AvilixCartDrop");data.remove("AvilixCreateDropSource");
        // A surviving stack now contains material from another entity. Its old actor/owner
        // cannot be used as the actor of later world changes or attributed to all items.
        data.remove("AvilixCreateOrigin");
    }
    /** A merge destroys a UUID boundary shared by drop, suction and pickup histories. Record a
     * barrier for every involved construction until a complete multi-entity contract exists. */
    public static MergeCapture merging(ItemEntity first,ItemEntity second){
        if(!CreateCartAudit.enabled()||!(first.level() instanceof ServerLevel level)||second.level()!=level)return null;
        var a=origins(first);var b=origins(second);
        if(a.isEmpty()&&b.isEmpty()&&!mixed(first)&&!mixed(second))return null;
        return new MergeCapture(first,second,a,b,first.getItem().copy(),second.getItem().copy(),
                NbtSerde.snapshotEntity(level,first),NbtSerde.snapshotEntity(level,second));
    }
    public record MergeCapture(ItemEntity first,ItemEntity second,List<String> firstSources,List<String> secondSources,
                               ItemStack firstBefore,ItemStack secondBefore,CompoundTag firstEntityBefore,CompoundTag secondEntityBefore){
        private static CompoundTag endpoint(ItemEntity entity,ItemStack item,CompoundTag entityNbt,List<String> origins){
            var tag=new CompoundTag();tag.putUUID("WorldEntity",entity.getUUID());
            tag.putString("Dimension",entity.level().dimension().location().toString());tag.putString("EntityType","minecraft:item");
            tag.putLong("Pos",entity.blockPosition().asLong());tag.putBoolean("Exists",!item.isEmpty());
            var sources=new ListTag();for(String origin:origins)sources.add(StringTag.valueOf(origin));tag.put("Origins",sources);
            if(!item.isEmpty()){
                var stack=NbtSerde.snapshotItemStack(item,entity.registryAccess());
                if(stack!=null&&stack.sizeInBytes()<=1024*1024)tag.put("Item",stack);
                else tag.putBoolean("Truncated",true);
                if(entityNbt!=null&&entityNbt.sizeInBytes()<=1024*1024)tag.put("EntityNBT",entityNbt.copy());
                else tag.putBoolean("Truncated",true);
            }
            return tag;
        }
        public void finish(boolean success){
            boolean changedFirst=first.isRemoved()||!ItemStack.matches(firstBefore,first.getItem());
            boolean changedSecond=second.isRemoved()||!ItemStack.matches(secondBefore,second.getItem());
            if(success&&!changedFirst&&!changedSecond)return;
            if(!(first.level() instanceof ServerLevel level))return;
            var sources=new LinkedHashSet<String>();sources.addAll(firstSources);sources.addAll(secondSources);
            var combined=List.copyOf(sources);
            var mergeId=UUID.randomUUID();var chain=new ListTag();var known=new LinkedHashSet<UUID>();
            boolean overflow=false;
            for(var entity:List.of(first,second)){
                var data=entity instanceof CreateOriginDataAccess access?access.avilixlogger$originData():null;
                if(data==null)continue;
                var history=data.getList(MERGES,10);
                overflow|=data.getBoolean(MERGE_OVERFLOW);
                for(var tag:history){
                    if(tag instanceof CompoundTag id&&id.hasUUID("Id"))known.add(id.getUUID("Id"));
                    else overflow=true;
                }
            }
            known.add(mergeId);if(known.size()>32)overflow=true;
            for(var id:known){if(chain.size()>=32)break;var entry=new CompoundTag();entry.putUUID("Id",id);chain.add(entry);}
            if(changedFirst||changedSecond){
                boolean firstGrew=!first.isRemoved()&&first.getItem().getCount()>firstBefore.getCount();
                boolean secondGrew=!second.isRemoved()&&second.getItem().getCount()>secondBefore.getCount();
                if(!first.isRemoved()&&(firstGrew||!firstGrew&&!secondGrew))markMixed(first,combined,chain,overflow);
                if(!second.isRemoved()&&(secondGrew||!firstGrew&&!secondGrew))markMixed(second,combined,chain,overflow);
            }
            var before=new CompoundTag();before.putInt("Format",1);before.putString("Kind","actor_result");before.putBoolean("Complete",false);
            var after=before.copy();before.putString("Operation","world_item_merge");after.putString("Operation","world_item_merge");
            before.putUUID("Merge",mergeId);after.putUUID("Merge",mergeId);
            after.putBoolean("MergeChainOverflow",overflow);
            var b=new ListTag();b.add(endpoint(first,firstBefore,firstEntityBefore,firstSources));b.add(endpoint(second,secondBefore,secondEntityBefore,secondSources));
            var a=new ListTag();a.add(endpoint(first,first.isRemoved()?ItemStack.EMPTY:first.getItem(),
                    first.isRemoved()?null:NbtSerde.snapshotEntity(level,first),origins(first)));
            a.add(endpoint(second,second.isRemoved()?ItemStack.EMPTY:second.getItem(),
                    second.isRemoved()?null:NbtSerde.snapshotEntity(level,second),origins(second)));
            before.put("Entities",b);after.put("Entities",a);
            int beforeCount=firstBefore.getCount()+secondBefore.getCount();
            int afterCount=(first.isRemoved()?0:first.getItem().getCount())+(second.isRemoved()?0:second.getItem().getCount());
            after.putBoolean("Conserved",success&&beforeCount==afterCount&&beforeCount>0
                    &&ItemStack.isSameItemSameComponents(firstBefore,secondBefore)
                    &&(first.isRemoved()||ItemStack.isSameItemSameComponents(firstBefore,first.getItem()))
                    &&(second.isRemoved()||ItemStack.isSameItemSameComponents(secondBefore,second.getItem()))
                    &&!b.getCompound(0).getBoolean("Truncated")&&!b.getCompound(1).getBoolean("Truncated")
                    &&!a.getCompound(0).getBoolean("Truncated")&&!a.getCompound(1).getBoolean("Truncated")&&!overflow);
            boolean reversible=after.getBoolean("Conserved")&&(first.isRemoved()!=second.isRemoved())
                    &&!firstSources.isEmpty()&&!secondSources.isEmpty()
                    &&sources.stream().map(CartAuditContext::id).allMatch(id->id!=null&&id.equals(CartAuditContext.id(combined.getFirst())));
            before.putBoolean("Complete",reversible);after.putBoolean("Complete",reversible);
            for(String source:sources){
                var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_TRANSFER;
                row.dim=level.dimension().location().toString();row.x=first.blockPosition().getX();row.y=first.blockPosition().getY();row.z=first.blockPosition().getZ();
                row.entityUuid=first.getUUID();row.entityType="minecraft:item";row.source=source;row.actorName="SYSTEM";
                row.extra="construction drop merged; both UUIDs and before/after stacks captured; complete="+reversible;
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before.copy());
                row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after.copy());
                LogIdGenerator.ensure(row);LoggerRuntime.storage(level).append(row);
            }
        }
    }
    public static boolean capturing(ItemEntity drop){var attempt=CURRENT.get();return attempt!=null&&attempt.drop==drop;}
    public static Attempt begin(ItemEntity drop,net.minecraft.world.entity.player.Player player){
        if(CURRENT.get()!=null || !CreateCartAudit.enabled() || drop.isRemoved() || !(player instanceof ServerPlayer recipient)
                || !(drop.level() instanceof ServerLevel) || source(drop)==null)return null;
        var attempt=new Attempt(drop,recipient);CURRENT.set(attempt);return attempt;
    }
    public static MixedPickup beginMixed(ItemEntity drop,net.minecraft.world.entity.player.Player player){
        if(!CreateCartAudit.enabled()||drop.isRemoved()||!(player instanceof ServerPlayer recipient)
                ||!(drop.level() instanceof ServerLevel)||!mixed(drop))return null;
        var sources=origins(drop);
        if(sources.isEmpty())return null;
        int size=recipient.getInventory().getContainerSize();
        var inventory=new ItemStack[Math.min(size,41)];
        for(int i=0;i<inventory.length;i++)inventory[i]=recipient.getInventory().getItem(i).copy();
        return new MixedPickup(drop,recipient,sources,drop.getItem().copy(),drop.blockPosition().immutable(),inventory,size,
                NbtSerde.snapshotEntity((ServerLevel)drop.level(),drop));
    }
    /** Preserve every known construction history after a mixed stack is picked up. */
    public record MixedPickup(ItemEntity drop,ServerPlayer player,List<String> sources,ItemStack before,BlockPos pos,
                              ItemStack[] inventory,int inventorySize,CompoundTag entityBefore){
        private static void item(CompoundTag target,ItemStack stack,ServerLevel level){
            if(stack.isEmpty())return;
            var payload=NbtSerde.snapshotItemStack(stack,level.registryAccess());
            if(payload!=null&&payload.sizeInBytes()<=1024*1024)target.put("Item",payload);
            else target.putBoolean("Truncated",true);
        }
        private static CompoundTag slot(int index,ItemStack stack,ServerLevel level){
            var value=new CompoundTag();value.putInt("Slot",index);item(value,stack,level);return value;
        }
        public void finish(boolean success){
            var remaining=drop.isRemoved()?ItemStack.EMPTY:drop.getItem().copy();
            if(!(drop.level() instanceof ServerLevel level))return;
            var oldSlots=new ListTag();var newSlots=new ListTag();
            int currentSize=player.getInventory().getContainerSize();
            for(int i=0;i<Math.min(inventory.length,currentSize);i++){
                var now=player.getInventory().getItem(i);
                if(!ItemStack.matches(inventory[i],now)){
                    oldSlots.add(slot(i,inventory[i],level));newSlots.add(slot(i,now,level));
                }
            }
            if(ItemStack.matches(before,remaining)&&!drop.isRemoved()&&oldSlots.isEmpty()&&inventorySize==currentSize)return;
            var b=new CompoundTag();b.putInt("Format",1);b.putString("Kind","actor_result");
            b.putString("Operation","mixed_world_item_pickup");b.putBoolean("Complete",false);
            b.putUUID("Drop",drop.getUUID());b.putUUID("Player",player.getUUID());
            b.putUUID("Pickup",UUID.randomUUID());
            b.put("MergeChain",drop.getPersistentData().getList(MERGES,10).copy());
            if(drop.getPersistentData().getBoolean(MERGE_OVERFLOW))b.putBoolean("MergeChainOverflow",true);
            b.putInt("InventorySize",inventorySize);b.put("Slots",oldSlots);item(b,before,level);
            if(entityBefore!=null&&entityBefore.sizeInBytes()<=1024*1024)b.put("EntityNBT",entityBefore.copy());
            else b.putBoolean("Truncated",true);
            if(inventorySize>inventory.length)b.putBoolean("RecipientTruncated",true);
            var a=b.copy();a.putInt("InventorySize",currentSize);a.put("Slots",newSlots);
            if(currentSize>inventory.length)a.putBoolean("RecipientTruncated",true);
            a.put("Item",new CompoundTag());a.remove("Truncated");item(a,remaining,level);
            a.remove("EntityNBT");
            if(!drop.isRemoved()){
                var entityAfter=NbtSerde.snapshotEntity(level,drop);
                if(entityAfter!=null&&entityAfter.sizeInBytes()<=1024*1024)a.put("EntityNBT",entityAfter);
                else a.putBoolean("Truncated",true);
            }
            int picked=before.getCount()-remaining.getCount();
            boolean complete=success&&!b.getBoolean("Truncated")&&!a.getBoolean("Truncated")
                    &&inventorySize==41&&currentSize==41&&oldSlots.size()>0&&picked>0
                    &&(remaining.isEmpty()||ItemStack.isSameItemSameComponents(before,remaining))
                    &&sources.stream().map(CartAuditContext::id).allMatch(id->id!=null&&id.equals(CartAuditContext.id(sources.getFirst())))
                    &&!b.getBoolean("MergeChainOverflow");
            b.putBoolean("Complete",complete);a.putBoolean("Complete",complete);
            for(String source:sources){
                var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_TRANSFER;
                row.dim=level.dimension().location().toString();row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
                row.entityUuid=drop.getUUID();row.entityType="minecraft:item";row.source=source;
                row.actorUuid=CauseContext.actorUuid(player);row.actorName=CauseContext.actorName(player);
                row.count=Math.max(0,before.getCount()-remaining.getCount());
                row.extra="mixed-origin item pickup; recipient="+player.getScoreboardName()+"; changed slots="+oldSlots.size()+"; native call="+(success?"completed":"failed");
                row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,b.copy());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,a.copy());
                LogIdGenerator.ensure(row);LoggerRuntime.storage(level).append(row);
            }
        }
    }
    public static void inventoryAdd(ItemEntity drop){var attempt=CURRENT.get();if(attempt!=null&&attempt.drop==drop&&attempt.scope==null){attempt.scope=new Scope(drop,attempt.player);if(!ItemStack.matches(attempt.original,attempt.scope.original))attempt.scope.complete=false;}}
    public static final class Attempt {
        private final ItemEntity drop;private final ServerPlayer player;private final ItemStack original;private Scope scope;
        private Attempt(ItemEntity drop,ServerPlayer player){this.drop=drop;this.player=player;original=drop.getItem().copy();}
        public void finish(boolean success){
            try{
                if(scope!=null)scope.finish(success);
                else if(drop.isRemoved()||!ItemStack.matches(original,drop.getItem())){
                    // A pickup pre-event changed/removed the drop without native inventory insertion.
                    var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.ITEM_PICKUP;
                    row.dim=drop.level().dimension().location().toString();row.entityUuid=drop.getUUID();row.actorUuid=CauseContext.actorUuid(player);row.actorName=CauseContext.actorName(player);
                    row.source=source(drop);row.extra="pickup pre-event changed drop without a complete recipient snapshot";
                    LogIdGenerator.ensure(row);if(CreateCartAudit.enabled())LoggerRuntime.storage((ServerLevel)drop.level()).append(row);
                }
            }finally{CURRENT.remove();}
        }
    }
    public static final class Scope {
        private final ItemEntity drop;private final ServerPlayer player;private final String source;private final UUID id=UUID.randomUUID();
        private final ItemStack original;private final ItemStack[] inventory;private final BlockPos pos;
        private boolean complete=true,finished;
        private Scope(ItemEntity drop,ServerPlayer player){
            this.drop=drop;this.player=player;source=source(drop);pos=drop.blockPosition().immutable();
            original=drop.getItem().copy();int size=player.getInventory().getContainerSize();complete=size==41;
            inventory=new ItemStack[Math.min(size,41)];for(int i=0;i<inventory.length;i++)inventory[i]=player.getInventory().getItem(i).copy();
        }
        public LogEntry finish(boolean success){
            if(finished)throw new IllegalStateException("Снимок подбора уже завершён");finished=true;
            // Vanilla restores the removed entity's stack count for statistics after discard.
            ItemStack remaining=drop.isRemoved()?ItemStack.EMPTY:drop.getItem().copy();
            CompoundTag before=envelope(),after=envelope();ListTag bs=new ListTag(),as=new ListTag();
            for(int i=0;i<inventory.length;i++){
                var now=player.getInventory().getItem(i);
                if(!ItemStack.matches(inventory[i],now)){bs.add(slot(i,inventory[i]));as.add(slot(i,now));}
            }
            if(ItemStack.matches(original,remaining)&&bs.isEmpty())return null;
            before.put("Slots",bs);after.put("Slots",as);before.put("Item",item(original));after.put("Item",item(remaining));
            int picked=original.getCount()-remaining.getCount();
            boolean valid=success&&complete&&!original.isEmpty()&&picked>0
                    &&(remaining.isEmpty()||ItemStack.isSameItemSameComponents(original,remaining))
                    &&inventory.length==player.getInventory().getContainerSize();
            before.putBoolean("Complete",valid);after.putBoolean("Complete",valid);
            LogEntry row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.ITEM_PICKUP;
            row.dim=drop.level().dimension().location().toString();row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
            row.actorUuid=CauseContext.actorUuid(player);row.actorName=CauseContext.actorName(player);row.entityUuid=drop.getUUID();row.entityType="minecraft:item";
            row.source=source;row.count=Math.max(0,picked);
            row.extra="linked pickup; transfer="+id+"; remaining="+remaining.getCount();
            row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.PLAYER_INV_AFTER,after);
            if(valid)row.deferSnapshot(LogEntry.SnapshotField.ITEM,item(original.copyWithCount(picked)));
            LogIdGenerator.ensure(row);
            if(CreateCartAudit.enabled())LoggerRuntime.storage((ServerLevel)drop.level()).append(row);
            return row;
        }
        private CompoundTag envelope(){var n=new CompoundTag();n.putInt("Format",1);n.putString("Kind","loot_items");n.putUUID("Transfer",id);n.putUUID("Player",player.getUUID());n.putUUID("Drop",drop.getUUID());n.putInt("Changes",0);return n;}
        private CompoundTag item(ItemStack stack){if(stack.isEmpty())return new CompoundTag();var n=NbtSerde.snapshotItemStack(stack,player.registryAccess());if(n==null){complete=false;return new CompoundTag();}return n;}
        private CompoundTag slot(int index,ItemStack stack){var n=new CompoundTag();n.putInt("Slot",index);if(!stack.isEmpty())n.put("Item",item(stack));return n;}
    }
}
