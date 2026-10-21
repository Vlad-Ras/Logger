package com.roften.avilixlogger.compat.create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.graph.DimensionPalette;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Durable carriage payload shared by its dimensional representations. No entity is spawned here. */
public final class CreateTrainRepresentationState {
    private CreateTrainRepresentationState() {}
    public record Prepared(UUID train,UUID carriage,int index,CompoundTag nativeCarriage,CompoundTag palette) {}
    public static CompoundTag capture(CarriageContraptionEntity entity) {
        var carriage=entity.getCarriage();
        if(!(entity.level() instanceof ServerLevel level)||carriage==null||carriage.train==null||carriage.train.carriages.indexOf(carriage)!=entity.carriageIndex)
            throw new IllegalStateException("Нет адресуемого вагона");
        var angles=new IdentityHashMap<CreateOrientationAccess,Boolean>();
        carriage.forEachPresentEntity(e->{if(e instanceof CreateOrientationAccess a)angles.put(a,a.avilixlogger$forceAngle());});
        var tag=new CompoundTag();var palette=new DimensionPalette();
        try{tag.put("Native",carriage.write(palette,level.registryAccess()));}finally{angles.forEach(CreateOrientationAccess::avilixlogger$forceAngle);}
        palette.write(tag);tag.putInt("Format",1);tag.putUUID("Train",carriage.train.id);tag.putUUID("Carriage",((ContraptionIdentity)carriage).avilixlogger$identity());tag.putInt("Index",entity.carriageIndex);
        tag.putInt("CargoVersion",carriage.storage.getVersion());
        prepare(tag);return tag;
    }
    /** Pure preparation for a future reserved representation restore, also accepts unloaded dimensions. */
    public static Prepared prepare(CompoundTag tag) {
        if(tag.getInt("Format")!=1||!tag.hasUUID("Train")||!tag.hasUUID("Carriage")||!tag.contains("Index",3)||tag.getInt("Index")<0||tag.getInt("Index")>=64||!tag.contains("CargoVersion",3)||!tag.contains("Native",10)||tag.sizeInBytes()>128L*1024*1024)
            throw new IllegalStateException("Неполный снимок представлений поезда");
        var nativeData=tag.getCompound("Native");
        if(!nativeData.hasUUID("AvilixCarriageId")||!nativeData.getUUID("AvilixCarriageId").equals(tag.getUUID("Carriage"))||!nativeData.contains("Entity",10)||nativeData.getCompound("Entity").isEmpty()||!nativeData.contains("Passengers",10))throw new IllegalStateException("Потерян payload вагона");
        var palette=CreatePlayerProgress.rows(tag,"DimensionPalette",16);var positions=CreatePlayerProgress.rows(nativeData,"EntityPositioning",16);
        if(positions.isEmpty())throw new IllegalStateException("Нет позиций представлений вагона");
        var dimensions=new HashSet<String>();for(var raw:palette){String id=((CompoundTag)raw).getString("Id");if(net.minecraft.resources.ResourceLocation.tryParse(id)==null||!dimensions.add(id))throw new IllegalStateException("Повреждена палитра измерений вагона");}
        var seen=new HashSet<Integer>();for(var raw:positions){var position=(CompoundTag)raw;int dim=position.getInt("Dim");
            if(!position.contains("Dim",3)||dim<0||dim>=palette.size()||!seen.add(dim))throw new IllegalStateException("Повреждён адрес представления вагона");
        }
        var dimensionsTag=new CompoundTag();dimensionsTag.put("DimensionPalette",palette.copy());
        return new Prepared(tag.getUUID("Train"),tag.getUUID("Carriage"),tag.getInt("Index"),nativeData.copy(),dimensionsTag);
    }
}
