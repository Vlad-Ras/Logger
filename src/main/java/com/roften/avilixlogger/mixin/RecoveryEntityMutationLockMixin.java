package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;
import java.util.UUID;

@Mixin(Entity.class)
public abstract class RecoveryEntityMutationLockMixin {
    @Inject(method={"setRemoved","kill","load","setPosRaw","setRot","removeVehicle"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$freeze(CallbackInfo result) {if(RecoveryEntityReservations.locked((Entity)(Object)this))result.cancel();}
    @Inject(method="setUUID",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$uuid(UUID id,CallbackInfo result) {
        var entity=(Entity)(Object)this;
        if(entity.isAddedToLevel()&&(RecoveryEntityReservations.locked(entity)||RecoveryEntityReservations.reserved(id)))result.cancel();
    }
    @Inject(method="startRiding(Lnet/minecraft/world/entity/Entity;Z)Z",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$relationship(Entity vehicle,boolean force,CallbackInfoReturnable<Boolean> result) {
        if(RecoveryEntityReservations.locked((Entity)(Object)this)||RecoveryEntityReservations.locked(vehicle))result.setReturnValue(false);
    }
}
