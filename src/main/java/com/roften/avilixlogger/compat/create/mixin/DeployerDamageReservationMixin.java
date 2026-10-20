package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.core.CartAuditContext;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.decoration.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Native damage must not mutate a reserved target between apply and compensation. */
@Mixin({LivingEntity.class,ArmorStand.class,ItemFrame.class,ItemEntity.class,ExperienceOrb.class})
public abstract class DeployerDamageReservationMixin {
    @Inject(method="hurt",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$damage(DamageSource damage,float amount,CallbackInfoReturnable<Boolean> cir){
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&CartAuditContext.LOCKED_ENTITIES.contains(((Entity)(Object)this).getUUID())){cir.setReturnValue(false);return;}
        if(com.roften.avilixlogger.compat.create.CreateDeployerAudit.capturing()&&!CartAuditContext.restoring())
            com.roften.avilixlogger.compat.create.CreateDeployerTransaction.entityBefore((Entity)(Object)this);
    }
}
