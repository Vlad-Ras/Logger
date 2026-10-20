package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;

/** Reverse history for same-construction merge and pickup; rejects foreign and incomplete origins. */
final class CreateMixedDropTransferPlan {
    private final UUID cart;
    private final CreateWorldItemTransferPlan world;
    private final CartMenuTransferPlan recipients;
    private final Set<UUID> entities=new HashSet<>();
    private final Set<UUID> merges=new HashSet<>(),pickups=new HashSet<>();
    CreateMixedDropTransferPlan(UUID cart,CreateWorldItemTransferPlan world,CartMenuTransferPlan recipients){
        this.cart=cart;this.world=world;this.recipients=recipients;
    }
    Set<UUID> entities(){return Set.copyOf(entities);}
    long add(CompoundTag before,CompoundTag after,String dimension,long pos){
        if(before==null||after==null||before.getInt("Format")!=1||after.getInt("Format")!=1
                ||!before.getString("Kind").equals("actor_result")||!after.getString("Kind").equals("actor_result")
                ||!before.getBoolean("Complete")||!after.getBoolean("Complete"))throw new IllegalStateException("Смешанный дроп захвачен не полностью");
        switch(after.getString("Operation")){
            case "world_item_merge" -> merge(before,after);
            case "mixed_world_item_pickup" -> pickup(before,after,dimension,pos);
            default -> throw new IllegalStateException("Неизвестное действие смешанного дропа");
        }
        return before.sizeInBytes()+after.sizeInBytes();
    }
    private void merge(CompoundTag b,CompoundTag a){
        if(!b.hasUUID("Merge")||!a.hasUUID("Merge")||!b.getUUID("Merge").equals(a.getUUID("Merge"))
                ||!merges.add(a.getUUID("Merge"))||a.getBoolean("MergeChainOverflow")||!a.getBoolean("Conserved"))
            throw new IllegalStateException("Цепочка слияний неполна или повторена");
        ListTag old=b.getList("Entities",10),now=a.getList("Entities",10);
        if(old.size()!=2||now.size()!=2)throw new IllegalStateException("Слияние не содержит обе сущности");
        long oldCount=0,newCount=0;int removed=0;CompoundTag kind=null;
        Set<UUID> pair=new HashSet<>();
        for(int i=0;i<2;i++){
            CompoundTag before=old.getCompound(i),after=now.getCompound(i);
            UUID id=before.hasUUID("WorldEntity")?before.getUUID("WorldEntity"):null;
            if(id==null||!pair.add(id)||!after.hasUUID("WorldEntity")||!id.equals(after.getUUID("WorldEntity"))
                    ||before.getBoolean("Truncated")||after.getBoolean("Truncated"))throw new IllegalStateException("Слияние потеряло UUID или снимок");
            origins(before);
            var first=before.getCompound("Item");var second=after.getCompound("Item");
            if(first.isEmpty())throw new IllegalStateException("Пустой исходный дроп слияния");
            var resource=first.copy();resource.remove("count");
            if(kind==null)kind=resource;else if(!kind.equals(resource))throw new IllegalStateException("Разные компоненты при слиянии");
            if(!second.isEmpty()){var result=second.copy();result.remove("count");if(!kind.equals(result))throw new IllegalStateException("Изменены компоненты слияния");}
            else removed++;
            oldCount+=quantity(first);newCount+=quantity(second);
            world.add(before,after);entities.add(id);
        }
        if(oldCount<=0||oldCount!=newCount||removed!=1)throw new IllegalStateException("Нарушен баланс слияния");
    }
    private void pickup(CompoundTag b,CompoundTag a,String dimension,long pos){
        if(!b.hasUUID("Pickup")||!a.hasUUID("Pickup")||!b.getUUID("Pickup").equals(a.getUUID("Pickup"))
                ||!pickups.add(a.getUUID("Pickup"))||!b.hasUUID("Drop")||!a.hasUUID("Drop")
                ||!b.getUUID("Drop").equals(a.getUUID("Drop"))||!b.hasUUID("Player")
                ||!a.hasUUID("Player")||!b.getUUID("Player").equals(a.getUUID("Player"))
                ||b.getInt("InventorySize")!=41||a.getInt("InventorySize")!=41
                ||b.getBoolean("Truncated")||a.getBoolean("Truncated")||b.getBoolean("MergeChainOverflow"))
            throw new IllegalStateException("Подбор смешанного дропа неполон");
        var before=endpoint(b,dimension,pos);var after=endpoint(a,dimension,pos);
        world.add(before,after);entities.add(b.getUUID("Drop"));
        var old=b.copy();var now=a.copy();
        old.putString("Kind","mixed_loot_items");now.putString("Kind","mixed_loot_items");
        old.putUUID("Transfer",b.getUUID("Pickup"));now.putUUID("Transfer",b.getUUID("Pickup"));
        old.putInt("Changes",0);now.putInt("Changes",0);
        recipients.manifest(old,now);
    }
    private CompoundTag endpoint(CompoundTag snapshot,String dimension,long pos){
        var endpoint=new CompoundTag();endpoint.putUUID("WorldEntity",snapshot.getUUID("Drop"));
        endpoint.putString("Dimension",dimension);endpoint.putString("EntityType","minecraft:item");endpoint.putLong("Pos",pos);
        if(snapshot.contains("Item",10))endpoint.put("Item",snapshot.getCompound("Item").copy());
        if(snapshot.contains("EntityNBT",10))endpoint.put("EntityNBT",snapshot.getCompound("EntityNBT").copy());
        endpoint.putBoolean("Exists",!snapshot.getCompound("Item").isEmpty());
        return endpoint;
    }
    private void origins(CompoundTag endpoint){
        var list=endpoint.getList("Origins",8);
        if(list.isEmpty()||list.size()>32)throw new IllegalStateException("Происхождение смешанного дропа неизвестно");
        for(int i=0;i<list.size();i++)if(!cart.equals(CartAuditContext.id(list.getString(i))))
            throw new IllegalStateException("Слияние затронуло другую конструкцию");
    }
    private static int quantity(CompoundTag item){return item.isEmpty()?0:item.contains("count",3)?item.getInt("count"):1;}
}
