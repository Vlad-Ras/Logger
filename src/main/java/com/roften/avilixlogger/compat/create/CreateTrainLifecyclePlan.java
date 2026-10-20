package com.roften.avilixlogger.compat.create;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
/** Detached train checkpoint, including unloaded carriages and both portal projections. */
public record CreateTrainLifecyclePlan(UUID id,CompoundTag registry,int carriages,int projections) {
    public static CreateTrainLifecyclePlan prepare(CompoundTag tag){
        if(tag==null||tag.getInt("TrainStateFormat")!=1||!tag.getString("Kind").equals("train_state")||!tag.getBoolean("Complete")
                ||!tag.contains("Registry",10)||tag.sizeInBytes()>64L*1024*1024)throw new IllegalStateException("Нет полного снимка поезда");
        var r=tag.getCompound("Registry");var n=r.getCompound("Native");
        if(!r.hasUUID("Id")||!n.hasUUID("Id")||!r.getUUID("Id").equals(n.getUUID("Id"))||!r.getBoolean("Registered")
                ||!r.contains("Runtime",10)||!r.contains("NavigationRuntime",10)||r.getCompound("Runtime").getBoolean("Invalid")
                ||(r.getInt("Moving")>=0)==(r.getInt("Waiting")>=0))throw new IllegalStateException("Идентичность или очередь поезда неполна");
        var id=r.getUUID("Id");var palette=list(r,"DimensionPalette",16);var dimensions=new HashSet<String>();
        if(palette.isEmpty())throw new IllegalStateException("Нет измерений поезда");
        for(var raw:palette){String name=((CompoundTag)raw).getString("Id");if(ResourceLocation.tryParse(name)==null||!dimensions.add(name))throw new IllegalStateException("Повреждены измерения поезда");}
        var cars=list(n,"Carriages",64);var runtime=list(r,"CarriageRuntime",64);var ids=new HashSet<UUID>();int count=0;
        if(cars.isEmpty()||cars.size()!=runtime.size()||n.getIntArray("CarriageSpacing").length!=cars.size()-1)throw new IllegalStateException("Неполный состав поезда");
        for(int i=0;i<cars.size();i++){var car=cars.getCompound(i);var entity=car.getCompound("Entity");
            if(!car.hasUUID("AvilixCarriageId")||!ids.add(car.getUUID("AvilixCarriageId"))||!entity.getString("id").equals("create:carriage_contraption")
                    ||!entity.hasUUID("TrainId")||!id.equals(entity.getUUID("TrainId"))||entity.getInt("CarriageIndex")!=i||entity.getCompound("Contraption").isEmpty()
                    ||!car.contains("Passengers",10)||!car.getCompound("Passengers").isEmpty()||car.sizeInBytes()>4L*1024*1024)throw new IllegalStateException("Неполный вагон, пассажиры или слишком большой снимок");
            var positions=list(car,"EntityPositioning",2);var seen=new HashSet<Integer>();CompoundTag cargo=null;
            if(positions.isEmpty())throw new IllegalStateException("Нет положения вагона");
            for(var raw:positions){var pos=(CompoundTag)raw;int dim=pos.getInt("Dim");
                if(!pos.contains("Dim",3)||dim<0||dim>=palette.size()||!seen.add(dim)||!pos.contains("Cutoff",5)||!Float.isFinite(pos.getFloat("Cutoff"))
                        ||Math.abs(pos.getFloat("Cutoff"))>1||!pos.contains("DiscardTicks",3)||pos.getInt("DiscardTicks")<0)throw new IllegalStateException("Повреждено представление вагона");
                var anchor=pos.getList("PositionAnchor",6);if(anchor.size()!=3)throw new IllegalStateException("Нет позиции вагона");
                for(int k=0;k<3;k++)if(!Double.isFinite(anchor.getDouble(k)))throw new IllegalStateException("Некорректная позиция вагона");
                if(list(pos,"RotationAnchors",2).size()!=2)throw new IllegalStateException("Нет поворота вагона");
                var storage=new CompoundTag();for(String key:List.of("items","fluids","TicksSinceLastExchange"))if(pos.contains(key))storage.put(key,pos.get(key).copy());
                if(cargo!=null&&!cargo.equals(storage))throw new IllegalStateException("Груз двух представлений различается");cargo=storage;
            }
            var extra=runtime.getCompound(i);var points=list(extra,"Points",4);
            if(!extra.contains("Blocked",1)||!extra.contains("CargoVersion",3)||points.size()!=(car.contains("SecondBogey",10)?4:2))throw new IllegalStateException("Неполный runtime вагона");
            for(var raw:points){var point=(CompoundTag)raw;if(!point.contains("Position",6)||!Double.isFinite(point.getDouble("Position"))||!point.contains("Blocked",1)||!point.contains("UpsideDown",1))throw new IllegalStateException("Повреждены колёсные точки");}
            count+=positions.size();
        }
        if(n.hasUUID("Graph")!=r.contains("Graph",10)||n.hasUUID("Graph")&&!n.getUUID("Graph").equals(r.getCompound("Graph").getCompound("Native").getUUID("Id")))throw new IllegalStateException("Нет исходного путевого графа");
        list(r,"Signals",512);if(n.hasUUID("Station")!=r.contains("Station",10))throw new IllegalStateException("Нет снимка станции");
        return new CreateTrainLifecyclePlan(id,r.copy(),cars.size(),count);
    }
    private static ListTag list(CompoundTag tag,String key,int limit){if(!(tag.get(key) instanceof ListTag list)||list.size()>limit||!list.isEmpty()&&list.getElementType()!=10)throw new IllegalStateException("Повреждён список поезда: "+key);return list;}
}
