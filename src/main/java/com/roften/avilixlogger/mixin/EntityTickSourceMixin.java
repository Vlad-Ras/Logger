package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.MutationSourceResolver;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(ServerLevel.class)
public abstract class EntityTickSourceMixin {
    @WrapMethod(method = "tickNonPassenger")
    private void avilixlogger$withSource(Entity entity, Operation<Void> original) {
        if (com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID())) return;
        if (!LoggerConfig.isEnabled()) { original.call(entity); return; }
        String source = MutationSourceResolver.sourceFor(entity);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try { original.call(entity); } finally { MutationSourceResolver.restore(previous); }
    }

    @WrapMethod(method = "tickPassenger")
    private void avilixlogger$withPassengerSource(Entity vehicle, Entity passenger, Operation<Void> original) {
        if (com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(passenger.getUUID())) return;
        if (!LoggerConfig.isEnabled()) { original.call(vehicle, passenger); return; }
        String source = MutationSourceResolver.sourceFor(passenger);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try { original.call(vehicle, passenger); } finally { MutationSourceResolver.restore(previous); }
    }
}
