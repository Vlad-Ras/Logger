package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateTrainRegistry;
import com.simibubi.create.content.trains.station.GlobalStation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(value=GlobalStation.class,remap=false)
public abstract class TrainStationReservationMixin {
    @Inject(method={"reserveFor","cancelReservation","trainDeparted"},at=@At("HEAD"),cancellable=true)
    private void avilixlogger$locked(CallbackInfo ci){if(CreateTrainRegistry.stationLocked(((GlobalStation)(Object)this).id))ci.cancel();}
}
