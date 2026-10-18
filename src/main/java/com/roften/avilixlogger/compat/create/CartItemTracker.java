package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import java.lang.ref.WeakReference;
import java.util.*;

/** Index only observed contraption items; never enumerate the world's entities/chunks. */
public final class CartItemTracker {
    private record Entry(UUID cart,WeakReference<ItemEntity> entity){}
    private static final Map<UUID,Entry> ENTITIES=new HashMap<>();
    private static final Map<UUID,Set<UUID>> CARTS=new HashMap<>();
    private CartItemTracker(){}
    public static void join(EntityJoinLevelEvent event){
        if(!(event.getEntity() instanceof ItemEntity item) || !(event.getLevel() instanceof ServerLevel) || !CreateCartAudit.enabled())return;
        UUID id=CreateCartAudit.itemId(item.getItem());if(id==null)return;
        ENTITIES.put(item.getUUID(),new Entry(id,new WeakReference<>(item)));
        CARTS.computeIfAbsent(id,ignored->new HashSet<>()).add(item.getUUID());
        record(item,id,"item_entity_loaded");
    }
    public static void leave(EntityLeaveLevelEvent event){
        if(!(event.getEntity() instanceof ItemEntity item))return;
        CartRollbackCoordinator.invalidated(item);
        Entry entry=ENTITIES.remove(item.getUUID());if(entry==null)return;
        var set=CARTS.get(entry.cart);if(set!=null){set.remove(item.getUUID());if(set.isEmpty())CARTS.remove(entry.cart);}
        if(CreateCartAudit.enabled())record(item,entry.cart,"item_entity_removed; reason="+item.getRemovalReason());
    }
    public static List<ItemEntity> loaded(UUID id){
        var ids=CARTS.get(id);if(ids==null)return List.of();
        ArrayList<ItemEntity> result=new ArrayList<>();
        for(UUID key:ids){var entry=ENTITIES.get(key);var item=entry==null?null:entry.entity.get();if(item!=null&&!item.isRemoved()&&id.equals(CreateCartAudit.itemId(item.getItem())))result.add(item);}
        return result;
    }
    private static void record(ItemEntity item,UUID id,String detail){
        if(!(item.level() instanceof ServerLevel level))return;
        LogEntry row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_TRANSFER;row.source=CartAuditContext.prefix(id)+"item:0";row.dim=level.dimension().location().toString();row.entityUuid=item.getUUID();row.x=item.blockPosition().getX();row.y=item.blockPosition().getY();row.z=item.blockPosition().getZ();row.extra=detail;
        var cause=CauseContext.peek();if(cause!=null){row.actorUuid=cause.actorUuid();row.actorName=cause.actorName();}
        LoggerRuntime.storage(level).append(row);
    }
    public static void clear(){ENTITIES.clear();CARTS.clear();}
}
