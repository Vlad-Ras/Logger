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
        if (com.roften.avilixlogger.core.CartRestoreLocks.blockLocked(blockEntity.getLevel(),blockEntity.getBlockPos())) return;
        if (blockEntity.getLevel() instanceof ServerLevel
                && com.roften.avilixlogger.core.CreateSupport.available()
                && (com.roften.avilixlogger.compat.create.CreateCapturePolicy.mutedController(blockEntity)
                    || "create".equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).getNamespace())
                    && !com.roften.avilixlogger.compat.create.CreateCapturePolicy.mechanismEnabled(blockEntity.getBlockState()))) {
            boolean previousCapture = com.roften.avilixlogger.core.CaptureTypePolicy.suppress(true);
            String previous=MutationSourceResolver.enter(MutationSourceResolver.VANILLA_SIMULATION);
            try(var ignored=com.roften.avilixlogger.core.CauseContext.unattributed()){original.call();}
            finally{MutationSourceResolver.restore(previous);
                com.roften.avilixlogger.core.CaptureTypePolicy.restoreSuppression(previousCapture);}return;
        }
        if (!(blockEntity.getLevel() instanceof ServerLevel) || !LoggerConfig.isTickCaptureEnabled()) {
            original.call();
            return;
        }
        String source = MutationSourceResolver.sourceFor(blockEntity);
        if (source == null) source = MutationSourceResolver.VANILLA_SIMULATION;
        String previous = MutationSourceResolver.enter(source);
        try (var actorScope = com.roften.avilixlogger.core.CauseContext.unattributed()) {
            var transfer=com.roften.avilixlogger.core.CartAutomationHooks.authorizedTick(blockEntity);
            boolean success=false;
            try { original.call();success=true; }
            finally { if(transfer!=null)transfer.finish(success); }
        } finally { MutationSourceResolver.restore(previous); }
    }
}
