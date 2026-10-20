package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateTrainCargoAccess;
import com.simibubi.create.content.contraptions.minecart.TrainCargoManager;
import org.spongepowered.asm.mixin.*;
import java.util.concurrent.atomic.AtomicInteger;
@Mixin(value=TrainCargoManager.class,remap=false)
public abstract class TrainCargoRuntimeMixin implements CreateTrainCargoAccess {
    @Shadow int ticksSinceLastExchange;
    @Shadow AtomicInteger version;
    public void avilixlogger$cargoRuntime(int idle,int revision){ticksSinceLastExchange=idle;version.set(revision);}
}
