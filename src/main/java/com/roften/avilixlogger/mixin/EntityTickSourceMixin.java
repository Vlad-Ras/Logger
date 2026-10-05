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
        String source = LoggerConfig.isEnabled() ? MutationSourceResolver.sourceFor(entity) : null;
        if (!LoggerConfig.isEnabled()) { original.call(entity); return; }
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        try (var scope = MutationSourceResolver.push(source)) { original.call(entity); }
    }

    @WrapMethod(method = "tickPassenger")
    private void avilixlogger$withPassengerSource(Entity vehicle, Entity passenger, Operation<Void> original) {
        String source = LoggerConfig.isEnabled() ? MutationSourceResolver.sourceFor(passenger) : null;
        if (!LoggerConfig.isEnabled()) { original.call(vehicle, passenger); return; }
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        try (var scope = MutationSourceResolver.push(source)) { original.call(vehicle, passenger); }
    }
}
