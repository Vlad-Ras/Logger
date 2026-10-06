package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.MutationSourceResolver;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Exact source for machine-driven changes, without a stack walk per changed block. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickSourceMixin {
    @Shadow @Final private BlockEntity blockEntity;

    @WrapMethod(method = "tick")
    private void avilixlogger$withSource(Operation<Void> original) {
        if (!LoggerConfig.isEnabled() || !(blockEntity.getLevel() instanceof ServerLevel)) {
            original.call();
            return;
        }
        String source = MutationSourceResolver.sourceFor(blockEntity);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try { original.call(); } finally { MutationSourceResolver.restore(previous); }
    }
}
