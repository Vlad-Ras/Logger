package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Reconstructs only the contraption from a full checkpoint and subsequent captured state. */
public record CartRecoveryPlan(UUID id,long entry,long timestamp,String side,CompoundTag item,int blocks,long snapshotEntry,int changes) {
    public CartRecoveryPlan(UUID id,long entry,long timestamp,String side,CompoundTag item,int blocks){this(id,entry,timestamp,side,item,blocks,entry,0);}
    public static CartRecoveryPlan prepare(LogStorage storage,UUID id,long entry){return prepare(storage,id,entry,null);}
    /** Replay only the construction's own state, bounded by the requested row/capture ID. */
    public static CartRecoveryPlan prepare(LogStorage storage,UUID id,long entry,net.minecraft.core.HolderLookup.Provider registries){
        if(entry<0)throw new IllegalArgumentException("Неверный entry");
        var q=new LogQuery();q.dim="*";q.cartId=id;q.requireDetails=true;q.untilTs=Long.MAX_VALUE;q.limit=256;
        if(entry>0&&entry<Long.MAX_VALUE)q.beforeId=entry+1;
        var changes=new ArrayList<LogEntry>();long bytes=0,target=entry,time=0;int examined=0;
        while(true){var rows=storage.queryReverse(q);if(rows.isEmpty())break;long next=Long.MAX_VALUE;
            for(var row:rows){
                if(!id.equals(CartAuditContext.id(row.source))||row.id<=0||q.beforeId>0&&row.id>=q.beforeId||row.id>=next)throw new IllegalStateException("Некорректный порядок истории штуковины");
                if(examined++==0){if(entry>0&&row.id!=entry)throw new IllegalStateException("Выбранная запись не найдена");target=row.id;time=row.ts;}
                next=row.id;if(examined>100000)throw new IllegalStateException("Больше 100000 записей; выберите более ранний момент");
                bytes+=(row.beBefore==null?0:row.beBefore.length()*2L)+(row.beAfter==null?0:row.beAfter.length()*2L);
                if(bytes>128L*1024*1024)throw new IllegalStateException("История состояния превышает 128 MiB");
                for(String side:List.of("after","before")){
                    var envelope=NbtSerde.fromSnbt(side.equals("after")?row.beAfter:row.beBefore);
                    if(!full(envelope))continue;
                    var base=fromSnapshot(id,row.id,row.ts,side,envelope);
                    // Before a removal/disassembly is the last recoverable whole construction.
                    if(side.equals("before")&&!CartRollbackPlan.checkpoint(row.type))throw new IllegalStateException("Неизвестный переход полного снимка");
                    Collections.reverse(changes);
                    var replay=new CartStateReplay(base.item(),registries);
                    for(var change:changes)replay.apply(change);
                    var wrapped=new CompoundTag();wrapped.putInt("Format",1);wrapped.putString("Form","item");wrapped.put("Item",replay.item());
                    var result=fromSnapshot(id,target,time,side,wrapped);
                    return new CartRecoveryPlan(id,target,time,side,result.item(),result.blocks(),row.id,replay.applied());
                }
                if(CartRollbackPlan.checkpoint(row.type))throw new IllegalStateException("Переход entry="+row.id+" не содержит полного снимка; восстановление устаревшего состояния остановлено");
                if(row.type==ActionType.CART_CONTENT_CHANGE||row.type==ActionType.CART_STRUCTURE_CHANGE||row.type==ActionType.CART_STATUS)changes.add(row);
            }
            if(rows.size()<q.limit)break;q.beforeId=next;
        }
        throw new IllegalStateException("Полного снимка нет. Для восстановления нужен сохранённый NBT конструкции");
    }
    public static boolean full(CompoundTag n){
        return n!=null&&n.getInt("Format")==1&&(n.getString("Form").equals("item")&&n.contains("Item",10)
                ||n.getString("Form").equals("entity")&&n.getCompound("Entity").contains("Contraption",10));
    }
    public static CartRecoveryPlan fromSnapshot(UUID id,long entry,long timestamp,String side,CompoundTag envelope){
        if(!full(envelope)||envelope.sizeInBytes()>16L*1024*1024)throw new IllegalStateException("Неполный или слишком большой снимок штуковины");
        CompoundTag item;
        if(envelope.getString("Form").equals("item"))item=envelope.getCompound("Item").copy();
        else{
            var entity=envelope.getCompound("Entity");var cart=envelope.getCompound("Cart");
            String type=switch(cart.getString("id")){
                case "minecraft:minecart"->"create:minecart_contraption";
                case "minecraft:chest_minecart"->"create:chest_minecart_contraption";
                case "minecraft:furnace_minecart"->"create:furnace_minecart_contraption";
                default->throw new IllegalStateException("Нет поддерживаемого типа базовой вагонетки");};
            if(!cart.getList("Items",10).isEmpty())throw new IllegalStateException("В самой вагонетке есть предметы; штатная упаковка Create их не сохраняет. Нужен возврат отдельного инвентаря вагонетки");
            if(entity.contains("OnCoupling")||!entity.getList("Passengers",10).isEmpty())throw new IllegalStateException("Сцепка/пассажиры требуют совместного восстановления");
            var contraption=entity.getCompound("Contraption").copy();
            if(!entity.contains("InitialOrientation"))throw new IllegalStateException("Нет исходного направления конструкции");
            contraption.put("InitialOrientation",entity.get("InitialOrientation").copy());
            // The same fields removed by Create's native MinecartContraptionItem.create.
            for(String field:List.of("UUID","Pos","Motion"))contraption.remove(field);
            item=new CompoundTag();item.putString("id",type);item.putInt("count",1);var components=new CompoundTag();
            components.put("create:minecart_contraption_data",contraption);
            if(cart.contains("CustomName",8))components.put("minecraft:custom_name",cart.get("CustomName").copy());item.put("components",components);
        }
        if(!Set.of("create:minecart_contraption","create:chest_minecart_contraption","create:furnace_minecart_contraption").contains(item.getString("id"))||item.getInt("count")!=1)throw new IllegalStateException("Снимок не содержит единичный предмет вагонеточной штуковины");
        var data=item.getCompound("components").getCompound("create:minecart_contraption_data");
        if(!data.getString("Type").equals("create:mounted")||!data.getCompound(CartAuditState.KEY).hasUUID("Id")||!id.equals(data.getCompound(CartAuditState.KEY).getUUID("Id")))throw new IllegalStateException("Тип или ID сохранённой штуковины не совпадает");
        if(!data.getList("SubContraptions",10).isEmpty())throw new IllegalStateException("Вложенная конструкция требует отдельного совместного восстановления");
        var blocks=data.getCompound("Blocks");var palette=blocks.getList("Palette",10);var list=blocks.getList("BlockList",10);
        if(list.isEmpty()||palette.isEmpty())throw new IllegalStateException("В снимке нет блоков конструкции");
        var positions=new HashSet<Long>();
        for(var raw:list){var block=(CompoundTag)raw;if(!block.contains("Pos",4)||!positions.add(block.getLong("Pos"))||!block.contains("State",3)||block.getInt("State")<0||block.getInt("State")>=palette.size())throw new IllegalStateException("Повреждена палитра блоков");}
        return new CartRecoveryPlan(id,entry,timestamp,side,item,list.size());
    }
}
