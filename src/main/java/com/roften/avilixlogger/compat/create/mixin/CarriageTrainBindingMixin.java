package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.compat.create.CreateCarriageEntityAccess;
import com.simibubi.create.content.trains.entity.*;
import net.minecraft.network.syncher.EntityDataAccessor;
import org.spongepowered.asm.mixin.*;
import java.util.*;
@Mixin(value=CarriageContraptionEntity.class,remap=false)
public abstract class CarriageTrainBindingMixin implements CreateCarriageEntityAccess {
    @org.spongepowered.asm.mixin.injection.Inject(method={"startControlling","control"},at=@org.spongepowered.asm.mixin.injection.At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$controlLock(org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> ci){if(com.roften.avilixlogger.compat.create.CreateCargoAudit.restoreLocked((CarriageContraptionEntity)(Object)this))ci.setReturnValue(false);}
    @Shadow private Carriage carriage;
    @Shadow public UUID trainId;
    @Shadow public int carriageIndex;
    @Shadow @Final private static EntityDataAccessor<Optional<UUID>> TRACK_GRAPH;
    public void avilixlogger$bind(Carriage restored){carriage=restored;trainId=restored.train.id;carriageIndex=restored.train.carriages.indexOf(restored);var self=(CarriageContraptionEntity)(Object)this;
        if(!(self.getContraption() instanceof CarriageContraption cc))throw new IllegalStateException("Нет payload вагона");cc.swapStorageAfterAssembly(self);self.getEntityData().set(TRACK_GRAPH,Optional.ofNullable(restored.train.graph==null?null:restored.train.graph.id));}
}
