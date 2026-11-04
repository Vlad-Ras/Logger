package com.roften.avilixlogger.compat.create;

import net.minecraft.nbt.CompoundTag;
import java.util.*;

/** Detached reverse continuity for the world end of a mounted funnel transfer. */
public final class CreateWorldItemTransferPlan {
    public record Undo(String dimension, UUID entity, String type, CompoundTag before, CompoundTag after) {}
    private final LinkedHashMap<UUID,Undo> entities=new LinkedHashMap<>();
    public void add(CompoundTag before,CompoundTag after) {
        if(!before.hasUUID("WorldEntity")||!after.hasUUID("WorldEntity")
                ||!before.getUUID("WorldEntity").equals(after.getUUID("WorldEntity"))
                ||!before.getString("Dimension").equals(after.getString("Dimension"))
                ||!before.getString("EntityType").equals(after.getString("EntityType")))throw new IllegalStateException("Адрес предметной сущности изменён внутри обмена");
        var id=after.getUUID("WorldEntity");String dimension=after.getString("Dimension"),type=after.getString("EntityType");
        if(dimension.isBlank()||!Set.of("minecraft:item","create:package").contains(type))throw new IllegalStateException("Неизвестный предметный endpoint");
        validate(before,id,type);validate(after,id,type);
        var previous=entities.get(id);
        if(previous!=null&&(!dimension.equals(previous.dimension)||!type.equals(previous.type)
                ||!after.getCompound("Item").equals(previous.before.getCompound("Item"))
                ||!CreateWorldItemRestore.stable(after.getCompound("EntityNBT")).equals(CreateWorldItemRestore.stable(previous.before.getCompound("EntityNBT")))))throw new IllegalStateException("Предметная сущность изменена между связанными обменами");
        entities.put(id,new Undo(dimension,id,type,before.copy(),previous==null?after.copy():previous.after));
    }
    private static void validate(CompoundTag endpoint,UUID id,String type) {
        var item=endpoint.getCompound("Item");var nbt=endpoint.getCompound("EntityNBT");
        if(item.isEmpty()!=nbt.isEmpty()||!nbt.isEmpty()&&(!nbt.hasUUID("UUID")||!id.equals(nbt.getUUID("UUID"))||!type.equals(nbt.getString("id"))
                ||nbt.getList("Pos",6).size()!=3))throw new IllegalStateException("Нет полного снимка предметной сущности");
        if(!endpoint.contains("Pos",4))throw new IllegalStateException("Нет адреса предметной сущности");
        if(!nbt.isEmpty()&&!item.equals(nbt.getCompound(type.equals("minecraft:item")?"Item":"Box")))throw new IllegalStateException("Снимок предмета расходится со снимком его сущности");
    }
    public List<Undo> finish(){return entities.values().stream().filter(e->!e.before.equals(e.after)).toList();}
}
