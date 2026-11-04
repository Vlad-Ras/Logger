package com.roften.avilixlogger.compat.create;
import com.roften.avilixlogger.core.*;
import com.simibubi.create.Create;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import java.util.*;
/** Coalesce actual load/unload/portal boundaries; no snapshots on idle train ticks. */
public final class CreateTrainLifecycleAudit {
    private record Pending(ServerLevel level,String phase){}
    private static final Map<Train,Pending> PENDING=new LinkedHashMap<>();
    public static void changed(Carriage carriage,ServerLevel level,String phase){
        if(!CreateCapturePolicy.kindEnabled(CreateContraptionKind.TRAIN)||!com.roften.avilixlogger.LoggerConfig.VALUES.logCreateLifecycle.get()||CartAuditContext.restoring()||!level.getServer().isSameThread()||!CreateCartAudit.enabled()||carriage.train==null)return;
        if(PENDING.size()<128||PENDING.containsKey(carriage.train))PENDING.put(carriage.train,new Pending(level,phase));
        else CreateCartAudit.failed("train lifecycle queue",new IllegalStateException("Очередь снимков поездов заполнена"));
    }
    public static CompoundTag capture(Train train,ServerLevel level,String phase){
        var tag=new CompoundTag();tag.putInt("TrainStateFormat",1);tag.putString("Kind","train_state");tag.putString("Phase",phase);tag.putBoolean("Complete",true);
        tag.put("Registry",CreateTrainRegistry.snapshot(train,level));var presence=new ListTag();
        for(int i=0;i<train.carriages.size();i++){var car=train.carriages.get(i);var dims=new ArrayList<>(car.getPresentDimensions());dims.sort(Comparator.comparing(d->d.location().toString()));
            for(var dim:dims){var entry=new CompoundTag();entry.putInt("CarriageIndex",i);entry.putUUID("Carriage",((ContraptionIdentity)car).avilixlogger$identity());entry.putString("Dimension",dim.location().toString());
                var entity=car.getDimensionalIfPresent(dim).entity.get();boolean loaded=entity!=null&&!entity.isRemoved();entry.putBoolean("Loaded",loaded);
                if(loaded){entry.putUUID("Entity",entity.getUUID());if(!entity.getPassengers().isEmpty())tag.putBoolean("Complete",false);}presence.add(entry);}}
        tag.put("Presence",presence);try{CreateTrainLifecyclePlan.prepare(tag);}catch(RuntimeException e){tag.putBoolean("Complete",false);tag.putString("Failure",Objects.toString(e.getMessage(),"Неполный снимок"));}return tag;
    }
    public static void onTick(ServerTickEvent.Post event){
        if(PENDING.isEmpty()||!event.hasTime())return;var iterator=PENDING.entrySet().iterator();var next=iterator.next();var train=next.getKey();var pending=next.getValue();iterator.remove();
        if(!CreateCapturePolicy.kindEnabled(CreateContraptionKind.TRAIN)||!com.roften.avilixlogger.LoggerConfig.VALUES.logCreateLifecycle.get()||Create.RAILWAYS.trains.get(train.id)!=train||CartAuditContext.restoring()||!CreateCartAudit.enabled())return;
        try{var tag=capture(train,pending.level,pending.phase);var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.CART_STATUS;row.actorName="SYSTEM";
            row.source=CreateContraptionKind.TRAIN.source(train.id,"state_checkpoint");row.dim=pending.level.dimension().location().toString();var position=train.carriages.getFirst().getDimensionalIfPresent(pending.level.dimension());
            if(position!=null&&position.positionAnchor!=null){row.x=(int)Math.floor(position.positionAnchor.x);row.y=(int)Math.floor(position.positionAnchor.y);row.z=(int)Math.floor(position.positionAnchor.z);}
            row.extra="train_state; phase="+pending.phase+"; carriages="+train.carriages.size()+"; complete="+tag.getBoolean("Complete");row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,tag);LogIdGenerator.ensure(row);LoggerRuntime.storage(pending.level).append(row);
        }catch(RuntimeException e){CreateCartAudit.failed("train lifecycle snapshot",e);}
    }
    public static void clear(){PENDING.clear();}
}

