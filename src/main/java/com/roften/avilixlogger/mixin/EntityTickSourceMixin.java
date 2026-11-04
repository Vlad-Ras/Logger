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
        if (com.roften.avilixlogger.core.CartAuditContext.rollbackActive && com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(entity.getUUID())) return;
        // Muted Create ticks bypass AuthCore lookup before generic source attribution.
        if (com.roften.avilixlogger.core.CreateSupport.available()
                && com.roften.avilixlogger.compat.create.CreateCapturePolicy.mutedEntity(entity)) {
            // Suppress the generic mod:create attribution too: otherwise BLOCK_CHANGE
            // snapshots would continue for muted bearings on every actor tick.
            String previous = MutationSourceResolver.enter(MutationSourceResolver.VANILLA_SIMULATION);
            try(var ignored=com.roften.avilixlogger.core.CauseContext.unattributed()){ original.call(entity); } finally { MutationSourceResolver.restore(previous); }
            return;
        }
        if (!LoggerConfig.isTickCaptureEnabled()) { original.call(entity); return; }
        String source = MutationSourceResolver.sourceFor(entity);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try (var cause = com.roften.avilixlogger.core.CreateEntityOrigin.cause(entity)) { original.call(entity); }
        finally { MutationSourceResolver.restore(previous); }
    }

    @WrapMethod(method = "tickPassenger")
    private void avilixlogger$withPassengerSource(Entity vehicle, Entity passenger, Operation<Void> original) {
        if (com.roften.avilixlogger.core.CartAuditContext.rollbackActive && com.roften.avilixlogger.core.CartAuditContext.LOCKED_ENTITIES.contains(passenger.getUUID())) return;
        // Muted Create ticks bypass AuthCore lookup before generic source attribution.
        if (com.roften.avilixlogger.core.CreateSupport.available()
                && com.roften.avilixlogger.compat.create.CreateCapturePolicy.mutedEntity(passenger)) {
            String previous = MutationSourceResolver.enter(MutationSourceResolver.VANILLA_SIMULATION);
            try(var ignored=com.roften.avilixlogger.core.CauseContext.unattributed()){ original.call(vehicle, passenger); } finally { MutationSourceResolver.restore(previous); }
            return;
        }
        if (!LoggerConfig.isTickCaptureEnabled()) { original.call(vehicle, passenger); return; }
        String source = MutationSourceResolver.sourceFor(passenger);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try (var cause = com.roften.avilixlogger.core.CreateEntityOrigin.cause(passenger)) { original.call(vehicle, passenger); }
        finally { MutationSourceResolver.restore(previous); }
    }
}
