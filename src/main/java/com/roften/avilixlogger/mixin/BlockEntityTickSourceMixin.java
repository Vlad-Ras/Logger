package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.compat.create.CreateCapturePolicy;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.CartAutomationHooks;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.roften.avilixlogger.core.CaptureTypePolicy;
import com.roften.avilixlogger.core.CauseContext;
import com.roften.avilixlogger.core.CreateContraptionKind;
import com.roften.avilixlogger.core.CreateSupport;
import com.roften.avilixlogger.core.MutationSourceResolver;
import com.roften.avilixlogger.core.HotPathMetrics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/** Exact source for machine-driven changes, without a stack walk per changed block. */
@Mixin(targets = "net.minecraft.world.level.chunk.LevelChunk$BoundTickingBlockEntity")
public abstract class BlockEntityTickSourceMixin {
    @Shadow @Final private BlockEntity blockEntity;

    @Unique private String avilixlogger$source;
    @Unique private boolean avilixlogger$createType;
    @Unique private CreateContraptionKind avilixlogger$controllerKind;
    @Unique private Block avilixlogger$lastBlock;
    @Unique private int avilixlogger$mechanismGroup;

    @WrapMethod(method = "tick")
    private void avilixlogger$withSource(Operation<Void> original) {
        if (!(blockEntity.getLevel() instanceof ServerLevel level)) {
            original.call();
            return;
        }
        long measured = HotPathMetrics.beginBlockEntityTick();
        if (CartAuditContext.rollbackActive && CartRestoreLocks.blockLocked(level, blockEntity.getBlockPos())) {
            if (measured != 0) HotPathMetrics.blockEntityTick(measured, 0, 0);
            return;
        }

        // The ticker owns a final block entity. Only its block (and the live policy) can change.
        if (avilixlogger$source == null) {
            var source = MutationSourceResolver.sourceFor(blockEntity);
            avilixlogger$source = source == null ? MutationSourceResolver.VANILLA_SIMULATION : source;
            if (CreateSupport.available()) {
                var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntity.getType());
                avilixlogger$createType = "create".equals(type.getNamespace());
                avilixlogger$controllerKind = CreateCapturePolicy.controllerKind(type);
            }
        }
        boolean muted = false;
        if (avilixlogger$controllerKind != null) {
            var block = blockEntity.getBlockState().getBlock();
            if (block != avilixlogger$lastBlock) {
                var key = BuiltInRegistries.BLOCK.getKey(block);
                avilixlogger$mechanismGroup = "create".equals(key.getNamespace())
                        ? CreateCapturePolicy.mechanismGroup(key) : 0;
                avilixlogger$lastBlock = block;
            }
            muted = (avilixlogger$controllerKind != CreateContraptionKind.UNKNOWN
                    && !CreateCapturePolicy.kindEnabled(avilixlogger$controllerKind))
                    || (avilixlogger$mechanismGroup != 0 && !CaptureTypePolicy.enabled(avilixlogger$mechanismGroup));
        }
        if (!muted && !LoggerConfig.isTickCaptureEnabled()) {
            if (measured != 0) HotPathMetrics.blockEntityTick(measured, 0, 0);
            original.call();
            return;
        }

        boolean previousSuppression = muted && CaptureTypePolicy.suppress(true);
        String previous = MutationSourceResolver.enter(muted ? MutationSourceResolver.VANILLA_SIMULATION : avilixlogger$source);
        long nativeStart = 0, nativeEnd = 0;
        try (var actorScope = CauseContext.unattributed()) {
            var transfer = !muted && avilixlogger$createType
                    ? CartAutomationHooks.authorizedCreateTick(blockEntity, level) : null;
            boolean success = false;
            if (measured != 0) nativeStart = System.nanoTime();
            try {
                original.call();
                success = true;
            } finally {
                if (measured != 0) nativeEnd = System.nanoTime();
                if (transfer != null) transfer.finish(success);
            }
        } finally {
            MutationSourceResolver.restore(previous);
            if (muted) CaptureTypePolicy.restoreSuppression(previousSuppression);
            if (measured != 0) HotPathMetrics.blockEntityTick(measured, nativeStart, nativeEnd);
        }
    }
}
