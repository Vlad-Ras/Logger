package com.roften.avilixlogger.compat.backpacked;

import com.google.gson.JsonObject;
import com.mrcrayfish.backpacked.BackpackHelper;
import com.mrcrayfish.backpacked.inventory.*;
import com.mrcrayfish.backpacked.blockentity.*;
import com.mrcrayfish.backpacked.item.BackpackItem;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.backpacked.mixin.BackpackInventoryAccessor;
import com.roften.avilixlogger.compat.backpacked.mixin.BackpackShelfStackAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import java.util.*;

/** Native mutation hooks, no inventory polling. Detached snapshots are encoded by Logger's worker. */
public final class BackpackAudit {
    private BackpackAudit() {}
    public record Ref(ItemStack stack,ServerLevel level,BlockPos pos,ServerPlayer holder,String kind,int slot,UUID entity) {}
    private record Stamp(UUID actor,String name,String reason) {}
    private static final Map<Container,ItemStack[]> CONTENTS=new WeakHashMap<>();
    private static final Map<Container,Stamp> DIRTY=new LinkedHashMap<>();
    private static final ThreadLocal<Stamp> CURRENT=new ThreadLocal<>();
    private static int depth;
    private static final Scope INACTIVE=new Scope(null,List.of(),null,false);
    public static boolean restoring;
    public static boolean enabled(){return !restoring&&!CartAuditContext.restoring()&&LoggerConfig.isEnabled();}
    /** Rebase the native cache/index after a joint restore, without gameplay events or new IDs. */
    public static void rebase(Container container){
        CONTENTS.put(container,copySlots(container));DIRTY.remove(container);
        var ref=ref(container);if(ref==null)return;var id=BackpackIds.id(ref.stack);if(id==null)return;
        var index=BackpackIndex.get(ref.level.getServer());if(index==null)return;var record=index.record(id);if(record==null)return;
        locate(ref,record);index.snapshot(record,ref.stack,ref.level.registryAccess());index.touched(record);
    }
    public static boolean isBag(ItemStack stack){return !stack.isEmpty()&&stack.getItem() instanceof BackpackItem;}
    public static Ref ref(Container container){
        if(container instanceof BackpackInventory bag){
            Player player=((BackpackInventoryAccessor)bag).avilixlogger$player();
            int slot=((BackpackInventoryAccessor)bag).avilixlogger$index();
            if(player instanceof ServerPlayer p&&BackpackHelper.getBackpackStack(p,slot)==bag.getBackpackStack())return new Ref(bag.getBackpackStack(),p.serverLevel(),p.blockPosition(),p,"equipped",slot,null);
        }
        if(container instanceof BackpackDockBlockEntity.ItemStackContainer bag&&!bag.getBlockEntity().isRemoved()&&bag.getBlockEntity().getBackpack()==bag.getBackpackStack()&&(bag.getBlockEntity().getInventory()==null||bag.getBlockEntity().getInventory()==bag)&&bag.getBlockEntity().getLevel() instanceof ServerLevel level)
            return new Ref(bag.getBackpackStack(),level,bag.getBlockEntity().getBlockPos(),null,"dock",-1,null);
        if(container instanceof ShelfBlockEntity.BackpackShelfContainer bag&&!bag.getBlockEntity().isRemoved()&&((BackpackShelfStackAccessor)bag).avilixlogger$stack()==bag.getBlockEntity().getBackpack()&&bag.getBlockEntity().getLevel() instanceof ServerLevel level)
            return new Ref(((BackpackShelfStackAccessor)bag).avilixlogger$stack(),level,bag.getBlockEntity().getBlockPos(),null,"shelf",0,null);
        return null;
    }
    public static void initialize(Container container){
        if(!enabled())return;Ref ref=ref(container);if(ref==null||!isBag(ref.stack))return;
        ensure(ref,null,ActionType.BACKPACK_DISCOVER);CONTENTS.put(container,copySlots(container));
    }
    public static ItemStack[] copySlots(Container container){
        ItemStack[] items=new ItemStack[container.getContainerSize()];
        var raw=container instanceof com.roften.avilixlogger.compat.backpacked.mixin.BackpackRawContentsAccessor a?a.avilixlogger$items():null;
        for(int i=0;i<items.length;i++)items[i]=(raw==null?container.getItem(i):raw.get(i)).copy();return items;
    }
    public static void changed(Container container){
        if(!enabled())return;Ref ref=ref(container);if(ref==null||!isBag(ref.stack))return;
        if(!CONTENTS.containsKey(container)){initialize(container);return;}
        DIRTY.putIfAbsent(container,stamp("native_mutation"));
        if(depth==0)flush(container);
    }
    public static Scope scope(Player player,String reason){
        if(!enabled()||!(player instanceof ServerPlayer p))return INACTIVE;
        var access=(BackpackedInventoryAccess)p;List<Container> containers=new ArrayList<>();
        // Backpacked already evaluates these inventories for augment operations.
        access.backpacked$streamNonNullBackpackInventories().forEach(containers::add);
        if(p.containerMenu instanceof com.mrcrayfish.backpacked.inventory.container.BackpackContainerMenu menu&&!containers.contains(menu.getBackpackInventory()))containers.add(menu.getBackpackInventory());
        for(Container c:containers)if(!CONTENTS.containsKey(c))initialize(c);
        Stamp old=CURRENT.get();CURRENT.set(new Stamp(p.getUUID(),p.getGameProfile().getName(),reason));depth++;
        return new Scope(old,containers,p,true);
    }
    public static final class Scope implements AutoCloseable {
        private final Stamp old;private final List<Container> containers;private final ServerPlayer player;private final boolean active;private boolean closed;
        private final Map<UUID,CompoundTag> metadataBefore=new HashMap<>();
        private Scope(Stamp old,List<Container> containers,ServerPlayer player,boolean active){
            this.old=old;this.containers=containers;this.player=player;this.active=active;
            if(active&&CURRENT.get().reason.startsWith("setting:"))for(Container c:containers){Ref r=ref(c);if(r!=null){UUID id=BackpackIds.id(r.stack);if(id!=null)metadataBefore.put(id,metadataSnapshot(r.stack,r.level));}}
        }
        public void close(){
            if(closed||!active)return;closed=true;
            try{
                boolean direct=CURRENT.get().reason.equals("augment:reforge")||CURRENT.get().reason.equals("augment:immortal")||CURRENT.get().reason.equals("augment:quiverlink");
                for(Container c:containers){if(direct)DIRTY.putIfAbsent(c,stamp("native_mutation"));flush(c);}
                if(depth==1)for(Container c:new ArrayList<>(DIRTY.keySet()))flush(c);
                for(Container c:containers){Ref ref=ref(c);if(ref==null)continue;UUID id=BackpackIds.id(ref.stack);CompoundTag before=metadataBefore.get(id);
                    if(before!=null){CompoundTag after=metadataSnapshot(ref.stack,ref.level);if(!before.equals(after)){
                        var record=ensure(ref,null,ActionType.BACKPACK_DISCOVER);if(record!=null){event(ref,record,ActionType.BACKPACK_UPGRADE_CHANGE,stamp("settings_changed"),null,null,before,after);
                        }
                    }}
                }
                if(CURRENT.get().reason.startsWith("menu:")||CURRENT.get().reason.startsWith("setting:")){trackPlayer(player,ActionType.BACKPACK_TRANSFER);trackMenu(player);}
            }finally{depth--;CURRENT.set(old);}
        }
    }
    private static Stamp stamp(String fallback){
        Stamp value=CURRENT.get();if(value!=null)return value;
        CauseContext.Cause c=CauseContext.peek();String machine=MutationSourceResolver.resolveExternalSource();
        if(machine!=null)fallback=fallback+" via "+machine;
        return c==null?new Stamp(null,"SYSTEM",fallback):new Stamp(c.actorUuid(),c.actorName(),fallback);
    }
    public static BackpackIndex.Record ensure(Ref ref,Player creator,ActionType first){
        if(!enabled()||!isBag(ref.stack))return null;
        BackpackIndex index=BackpackIndex.get(ref.level.getServer());if(index==null)return null;
        UUID existing=BackpackIds.id(ref.stack);boolean fresh=existing==null||index.record(existing)==null;
        Player owner=creator!=null?creator:ref.holder;
        var r=index.identify(ref.stack,owner==null?null:owner.getUUID(),owner==null?null:owner.getName().getString());
        if(fresh)event(ref,r,first,stamp(first.name()),null,null);
        return r;
    }
    public static void lifecycle(Ref ref,Player actor,ActionType type,String reason){
        if(!enabled()||!isBag(ref.stack))return;
        var r=ensure(ref,actor,ActionType.BACKPACK_DISCOVER);if(r==null)return;
        Stamp s=actor==null?stamp(reason):new Stamp(actor.getUUID(),actor.getName().getString(),reason);
        event(ref,r,type,s,null,null);
    }
    private static void locate(Ref ref,BackpackIndex.Record r){
        r.dim=ref.level.dimension().location().toString();r.x=ref.pos.getX();r.y=ref.pos.getY();r.z=ref.pos.getZ();r.kind=ref.kind;r.slot=ref.slot;r.entity=ref.entity;
        r.holder=ref.holder==null?null:ref.holder.getUUID();r.holderName=ref.holder==null?"":ref.holder.getGameProfile().getName();
    }
    private static int stackCount(CompoundTag n){return n.contains("id")?(n.contains("count")?n.getInt("count"):1):0;}
    private static CompoundTag metadataSnapshot(ItemStack stack,ServerLevel level){ItemStack copy=stack.copy();copy.remove(DataComponents.CONTAINER);return (CompoundTag)copy.save(level.registryAccess());}
    public static LogEntry event(Ref ref,BackpackIndex.Record r,ActionType type,Stamp stamp,CompoundTag before,CompoundTag after){
        return event(ref,r,type,stamp,before,after,null,null);
    }
    private static LogEntry event(Ref ref,BackpackIndex.Record r,ActionType type,Stamp stamp,CompoundTag before,CompoundTag after,CompoundTag metadataBefore,CompoundTag metadataAfter){
        BackpackIndex index=BackpackIndex.get(ref.level.getServer());if(index==null)return null;
        boolean leaving=type==ActionType.BACKPACK_UNEQUIP||type==ActionType.BACKPACK_REMOVE;
        if(!leaving)locate(ref,r);r.reason=stamp.reason;
        if(!leaving)r.state=type==ActionType.BACKPACK_DESTROY?"destroyed":type==ActionType.BACKPACK_UNLOAD?"unloaded":type==ActionType.BACKPACK_UNEQUIP||type==ActionType.BACKPACK_REMOVE?"in_transit":"present";
        index.snapshot(r,ref.stack,ref.level.registryAccess());index.touched(r);
        LogEntry row=new LogEntry();row.ts=r.updated;row.type=type;row.dim=ref.level.dimension().location().toString();row.x=ref.pos.getX();row.y=ref.pos.getY();row.z=ref.pos.getZ();
        row.actorUuid=stamp.actor;row.actorName=stamp.name;row.entityUuid=r.id;row.entityType="backpacked:backpack";row.source=BackpackIds.prefix(r.id)+type.name();
        JsonObject extra=new JsonObject();extra.addProperty("backpackId",r.id.toString());extra.addProperty("shortId",r.shortId);extra.addProperty("owner",r.ownerName);extra.addProperty("holder",r.holderName);extra.addProperty("state",r.state);extra.addProperty("location",r.kind);extra.addProperty("reason",stamp.reason);if(before!=null&&after!=null&&before.getList("Items",Tag.TAG_COMPOUND).size()==1){
            CompoundTag b=before.getList("Items",Tag.TAG_COMPOUND).getCompound(0);CompoundTag a=after.getList("Items",Tag.TAG_COMPOUND).getCompound(0);
            extra.addProperty("slot",a.getInt("Slot"));extra.addProperty("beforeCount",stackCount(b));extra.addProperty("afterCount",stackCount(a));
            extra.addProperty("itemBefore",b.getString("id"));extra.addProperty("itemAfter",a.getString("id"));
            if(type==ActionType.BACKPACK_PUT||type==ActionType.BACKPACK_TAKE){row.count=Math.abs(stackCount(a)-stackCount(b));CompoundTag item=(type==ActionType.BACKPACK_PUT?a:b).copy();item.remove("Slot");item.putInt("count",Math.max(1,row.count));row.deferSnapshot(LogEntry.SnapshotField.ITEM,item);}
        }
        row.extra=extra.toString();
        if(before!=null)row.deferSnapshot(LogEntry.SnapshotField.SLOTS_BEFORE,before);
        if(after!=null)row.deferSnapshot(LogEntry.SnapshotField.SLOTS_AFTER,after);
        // A complete immutable stack includes all components, augments, cosmetics and locked slots.
        if(before==null&&after==null&&r.snapshot!=null)row.deferSnapshot(LogEntry.SnapshotField.ITEM,r.snapshot.copy());
        LogIdGenerator.ensure(row);r.lastEventId=row.id;if(type==ActionType.BACKPACK_DESTROY)r.lossId=row.id;
        if(metadataBefore!=null)row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,metadataBefore);
        if(metadataAfter!=null)row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,metadataAfter);
        LoggerRuntime.storage(ref.level).append(row);
        return row;
    }
    private static void flush(Container container){
        Stamp s=DIRTY.remove(container);if(s==null)return;Ref ref=ref(container);if(ref==null||!isBag(ref.stack))return;
        ItemStack[] old=CONTENTS.get(container);if(old==null){initialize(container);return;}
        ItemStack[] now=copySlots(container);var delta=BackpackSlotDelta.capture(old,now,ref.level.registryAccess());
        CONTENTS.put(container,now);if(delta==null)return;
        ItemStack full=ref.stack.copy();full.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(Arrays.asList(now)));
        Ref complete=new Ref(full,ref.level,ref.pos,ref.holder,ref.kind,ref.slot,ref.entity);var r=ensure(ref,null,ActionType.BACKPACK_DISCOVER);if(r==null)return;
        event(complete,r,delta.type(),s,delta.before(),delta.after());
        if(s.reason.startsWith("augment:"))event(complete,r,ActionType.BACKPACK_UPGRADE_USE,s,null,null);
    }
    public static void trackPlayer(ServerPlayer p,ActionType action){
        if(!enabled())return;
        for(int i=0;i<p.getInventory().getContainerSize();i++){ItemStack stack=p.getInventory().getItem(i);if(isBag(stack))track(new Ref(stack,p.serverLevel(),p.blockPosition(),p,"inventory",i,null),action);}
        ItemStack cursor=p.containerMenu.getCarried();if(isBag(cursor))track(new Ref(cursor,p.serverLevel(),p.blockPosition(),p,"cursor",-1,null),action);
        List<ItemStack> bags=BackpackHelper.getBackpacks(p);
        for(int i=0;i<bags.size();i++){ItemStack stack=bags.get(i);if(isBag(stack)){
            var inv=((BackpackedInventoryAccess)p).backpacked$GetBackpackInventory(i);if(inv!=null)inv.saveItemsToStack();
            track(new Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",i,null),action);
        }}
    }
    public static void trackMenu(ServerPlayer player){
        if(!enabled())return;
        for(var slot:player.containerMenu.slots){ItemStack stack=slot.getItem();if(!isBag(stack))continue;
            if(slot.container instanceof ShelfBlockEntity.ShelfContainer shelf&&shelf.getShelf().getLevel() instanceof ServerLevel level)
                track(new Ref(stack,level,shelf.getShelf().getBlockPos(),null,"shelf",slot.getContainerSlot(),null),ActionType.BACKPACK_TRANSFER);
            else if(slot.container instanceof net.minecraft.world.level.block.entity.BlockEntity be&&be.getLevel() instanceof ServerLevel level)
                track(new Ref(stack,level,be.getBlockPos(),null,"container",slot.getContainerSlot(),null),ActionType.BACKPACK_TRANSFER);
        }
    }

    public static void track(Ref ref,ActionType type){
        var r=ensure(ref,null,ActionType.BACKPACK_DISCOVER);if(r==null)return;
        boolean moved=!r.dim.equals(ref.level.dimension().location().toString())||!r.kind.equals(ref.kind)||r.slot!=ref.slot||!Objects.equals(r.entity,ref.entity)||!Objects.equals(r.holder,ref.holder==null?null:ref.holder.getUUID())||r.lost();
        if(moved||type==ActionType.BACKPACK_LOAD||type==ActionType.BACKPACK_UNLOAD)event(ref,r,type,stamp(type.name()),null,null);
        else {var index=BackpackIndex.get(ref.level.getServer());if(index.snapshot(r,ref.stack,ref.level.registryAccess()))event(ref,r,CURRENT.get()!=null&&CURRENT.get().reason.startsWith("setting:")?ActionType.BACKPACK_UPGRADE_CHANGE:ActionType.BACKPACK_STATE,stamp("components_changed"),null,null);locate(ref,r);}
    }
    public static Ref itemRef(ItemEntity e){return new Ref(e.getItem(),(ServerLevel)e.level(),e.blockPosition(),null,"item",-1,e.getUUID());}
    public static void metadataChanged(ItemStack stack,ServerPlayer p,ActionType type,String reason){
        if(!enabled()||!isBag(stack))return;
        lifecycle(new Ref(stack,p.serverLevel(),p.blockPosition(),p,"equipped",-1,null),p,type,reason);
    }
    public static void stop(){CONTENTS.clear();DIRTY.clear();CURRENT.remove();depth=0;restoring=false;}
}
