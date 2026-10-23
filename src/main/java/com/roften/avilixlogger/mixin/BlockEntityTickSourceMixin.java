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
        // Bearing controller ticks must not pay authorization, actor and NBT audit costs
        // when the corresponding Create contraption type is switched off.
        if (blockEntity.getLevel() instanceof ServerLevel
                && com.roften.avilixlogger.core.CreateSupport.available()
                && !LoggerConfig.VALUES.logCreateBearings.get()
                && blockEntity.getClass().getName().contains("Bearing")) {
            var type = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
            if ("create".equals(type.getNamespace())
                    && (type.getPath().contains("bearing") || type.getPath().contains("clockwork"))) {
                String previous = MutationSourceResolver.enter(MutationSourceResolver.VANILLA_SIMULATION);
                try (var ignored = com.roften.avilixlogger.core.CauseContext.unattributed()) { original.call(); }
                finally { MutationSourceResolver.restore(previous); }
                return;
            }
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
