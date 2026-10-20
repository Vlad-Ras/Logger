package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.*;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
/** Prevent native deletion of a reserved direct target before compensated restore finishes. */
@Mixin(Entity.class)
public abstract class DeployerTargetReservationMixin {
    @org.spongepowered.asm.mixin.Unique private boolean avilixlogger$locked(){return CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(((Entity)(Object)this).getUUID());}
    @Inject(method={"load","setCustomName"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$data(org.spongepowered.asm.mixin.injection.callback.CallbackInfo ci){if(avilixlogger$locked())ci.cancel();}
    @Inject(method="setRemoved",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$target(Entity.RemovalReason reason,CallbackInfo ci){
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(((Entity)(Object)this).getUUID()))ci.cancel();
    }
}
