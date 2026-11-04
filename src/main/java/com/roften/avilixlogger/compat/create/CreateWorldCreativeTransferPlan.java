package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import java.util.*;

/** Immutable IO at a world creative block; the template does not change during supply/void. */
public final class CreateWorldCreativeTransferPlan {
    public record IO(CompoundTag address) {}
    private final List<IO> operations=new ArrayList<>();

    public void add(CompoundTag before,CompoundTag after,java.util.function.BiConsumer<String,CompoundTag> inbound,
                    java.util.function.BiConsumer<String,CompoundTag> outbound){
        if(before.contains("WorldCreativeIO")!=after.contains("WorldCreativeIO"))throw new IllegalStateException("Неполный creative IO мира");
        if(!before.contains("WorldCreativeIO"))return;
        ListTag old=before.getList("WorldCreativeIO",10),now=after.getList("WorldCreativeIO",10);
        if(old.size()!=now.size()||old.isEmpty()||old.size()>512)throw new IllegalStateException("Некорректное число creative операций мира");
        for(int i=0;i<now.size();i++){
            var entry=now.getCompound(i);
            if(!entry.equals(old.getCompound(i)))throw new IllegalStateException("Creative операция мира различается между снимками");
            String kind=entry.getString("Kind"),direction=entry.getString("Direction");
            if(!Set.of("item","fluid").contains(kind)||!Set.of("in","out").contains(direction)
                    ||entry.getString("Dimension").isBlank()||!entry.contains("Pos",4)
                    ||!entry.contains("Block",10)||entry.getCompound("Block").getString("Name").isBlank()
                    ||!entry.getCompound("Block").getString("Name").equals(kind.equals("item")?"create:creative_crate":"create:creative_fluid_tank")
                    ||!entry.getString("BlockEntity").equals(kind.equals("item")?"create:creative_crate":"create:creative_fluid_tank")
                    ||!entry.contains("Size",3)||entry.getInt("Size")!=(kind.equals("item")?2:1)
                    ||!entry.contains("Limit",3)||entry.getInt("Limit")<=0
                    ||!Set.of("none","down","up","north","south","west","east").contains(entry.getString("Side"))
                    ||!entry.getString("Handler").equals(kind.equals("item")
                        ?"com.simibubi.create.content.logistics.crate.BottomlessItemHandler"
                        :"com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity$CreativeSmartFluidTank"))
                throw new IllegalStateException("Неизвестный creative endpoint мира");
            var payload=entry.getCompound("Payload");var template=entry.getCompound("Template");
            String quantity=kind.equals("item")?"count":"amount";
            int count=payload.getInt(quantity);
            if(payload.getString("id").isBlank()||count<=0||!entry.contains("Payload",10)
                    ||!entry.contains("Template",10)||entry.sizeInBytes()>128*1024
                    ||!template.isEmpty()&&(template.getString("id").isBlank()||template.getInt(quantity)<=0))
                throw new IllegalStateException("Неполный creative предмет или жидкость");
            if(direction.equals("out")){
                var supplied=template.copy();var delivered=payload.copy();supplied.remove(quantity);delivered.remove(quantity);
                if(template.isEmpty()||count>template.getInt(quantity)||!supplied.equals(delivered))
                    throw new IllegalStateException("Выдача не соответствует creative шаблону");
            }
            if(direction.equals("in"))inbound.accept(kind,payload);
            else outbound.accept(kind,payload);
            operations.add(new IO(entry.copy()));
        }
    }
    public List<IO> finish(){return List.copyOf(operations);}
}
