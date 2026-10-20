package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateKineticRegistry;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.content.kinetics.*;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(value=TorquePropagator.class,remap=false)
public abstract class TorqueNetworkReservationMixin {
    @Inject(method="getOrCreateNetworkFor",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$locked(KineticBlockEntity be,CallbackInfoReturnable<KineticNetwork> cir){
        if(be.network!=null&&be.getLevel() instanceof ServerLevel level&&CartRestoreLocks.resourceLocked(CreateKineticRegistry.key(level,be.network))){var network=CreateKineticRegistry.networks(level).get(be.network);if(network==null){network=new KineticNetwork();network.id=be.network;}CartRestoreLocks.handler(network);cir.setReturnValue(network);}
    }
}
