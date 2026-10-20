package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freeze reserved endpoints between the bounded steps of a joint restore. */
@Mixin(ServerLevel.class)
public abstract class DeployerRuntimeReservationMixin {
    @Inject(method="tickNonPassenger",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$runtimeTick(Entity entity,CallbackInfo ci){
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID()))ci.cancel();
    }
    @Inject(method="tickPassenger",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$passengerTick(Entity vehicle,Entity passenger,CallbackInfo ci){
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&(CartAuditContext.LOCKED_ENTITIES.contains(vehicle.getUUID())||CartAuditContext.LOCKED_ENTITIES.contains(passenger.getUUID())))ci.cancel();
    }
}
