package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.*;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.trains.GlobalRailwayManager;
import com.simibubi.create.content.trains.entity.Train;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.*;
@Mixin(value=GlobalRailwayManager.class,remap=false)
public abstract class RailwayTransactionMixin implements CreateRailwayAccess {
    @Shadow public Map<UUID,Train> trains;
    @Shadow private List<Train> movingTrains;
    @Shadow private List<Train> waitingTrains;
    public int avilixlogger$moving(UUID id){return avilixlogger$index(movingTrains,id);}
    public int avilixlogger$waiting(UUID id){return avilixlogger$index(waitingTrains,id);}
    @Unique private static int avilixlogger$index(List<Train> queue,UUID id){for(int i=0;i<queue.size();i++)if(queue.get(i).id.equals(id))return i;return -1;}
    public void avilixlogger$install(Train train,int moving,int waiting){avilixlogger$remove(train.id);trains.put(train.id,train);if(moving>=0)movingTrains.add(Math.min(moving,movingTrains.size()),train);if(waiting>=0)waitingTrains.add(Math.min(waiting,waitingTrains.size()),train);}
    public void avilixlogger$remove(UUID id){trains.remove(id);movingTrains.removeIf(t->t.id.equals(id));waitingTrains.removeIf(t->t.id.equals(id));}
    @Inject(method="tick",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$tick(CallbackInfo ci){if(CartRestoreLocks.resourceLocked(CreateTrainRegistry.RAILWAY_MUTATION))ci.cancel();}
    @Inject(method="addTrain",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$add(Train train,CallbackInfo ci){if(CreateTrainRegistry.locked(train.id))ci.cancel();}
    @Inject(method="removeTrain",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$remove(UUID id,CallbackInfo ci){if(CreateTrainRegistry.locked(id))ci.cancel();}
}
