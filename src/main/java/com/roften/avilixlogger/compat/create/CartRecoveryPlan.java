package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import java.util.*;

/** Selects an actual complete checkpoint. Never rewinds actions in the world. */
public record CartRecoveryPlan(UUID id,long entry,long timestamp,String side,CompoundTag item,int blocks) {
    public static CartRecoveryPlan prepare(LogStorage storage,UUID id,long entry){
        var q=new LogQuery();q.dim="*";q.cartId=id;q.requireDetails=true;q.untilTs=Long.MAX_VALUE;
        if(entry>0){q.afterId=entry-1;q.beforeId=entry==Long.MAX_VALUE?0:entry+1;q.limit=1;}
        else{q.types=EnumSet.of(ActionType.CART_ASSEMBLE,ActionType.CART_PLACE,ActionType.CART_PACK,ActionType.CART_DISASSEMBLE,ActionType.CART_REMOVE,ActionType.CART_ROLLBACK);q.limit=32;}
        int examined=0;
        while(true){var rows=storage.queryReverse(q);if(rows.isEmpty())break;
            long next=Long.MAX_VALUE;
            for(var row:rows){
                if(!id.equals(CartAuditContext.id(row.source))||entry>0&&row.id!=entry)throw new IllegalStateException("История вернула другую конструкцию/запись");
                next=Math.min(next,row.id);if(++examined>512)throw new IllegalStateException("Проверено 512 переходов; выберите entry в истории");
                for(String side:List.of("after","before")){
                    var envelope=NbtSerde.fromSnbt(side.equals("after")?row.beAfter:row.beBefore);
                    if(!full(envelope))continue;
                    // A malformed newest complete envelope is an error, never silently an older inventory.
                    return fromSnapshot(id,row.id,row.ts,side,envelope);
                }
            }
            if(entry>0||rows.size()<q.limit)break;
            if(q.beforeId>0&&next>=q.beforeId)throw new IllegalStateException("Курсор снимков не продвигается");q.beforeId=next;
        }
        throw new IllegalStateException("Полного снимка нет. Позиция/статус и отдельные действия не содержат всей штуковины");
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
        for(var raw:list){var block=(CompoundTag)raw;if(!block.contains("State",3)||block.getInt("State")<0||block.getInt("State")>=palette.size())throw new IllegalStateException("Повреждена палитра блоков");}
        return new CartRecoveryPlan(id,entry,timestamp,side,item,list.size());
    }
}
