package com.roften.avilixlogger.compat.create.mixin;

import com.roften.avilixlogger.core.CreateEntityOrigin;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class CreateEntityOriginMixin {
    @Inject(method="addFreshEntity", at=@At("HEAD"), require=1)
    private void avilixlogger$origin(Entity entity, CallbackInfoReturnable<Boolean> ci) {
        CreateEntityOrigin.capture(entity);
        if(entity instanceof net.minecraft.world.entity.item.ItemEntity item)com.roften.avilixlogger.compat.create.CreateActorEffectsAudit.spawning(item);
    }
    @Inject(method="addFreshEntity", at=@At("RETURN"), require=1)
    private void avilixlogger$spawned(Entity entity, CallbackInfoReturnable<Boolean> ci) {
        if(ci.getReturnValueZ() && entity instanceof net.minecraft.world.entity.item.ItemEntity item)
            com.roften.avilixlogger.compat.create.CreateActorEffectsAudit.spawned(item);
    }
}
