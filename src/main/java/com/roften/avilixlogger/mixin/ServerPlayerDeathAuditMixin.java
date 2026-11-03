package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.core.PlayerDeathAudit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerDeathAuditMixin {
    @WrapOperation(method="die",at=@At(value="INVOKE",target="Lnet/neoforged/neoforge/common/CommonHooks;onLivingDeath(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z"),require=1)
    private boolean avilixlogger$acceptedDeath(LivingEntity victim,DamageSource source,Operation<Boolean> original) {
        boolean canceled=original.call(victim,source);
        if(!canceled)PlayerDeathAudit.accepted((ServerPlayer)(Object)this,source);
        return canceled;
    }
}
