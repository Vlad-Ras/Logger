package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.MutationSourceResolver;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(LevelChunk.class)
public abstract class ChunkGenerationSourceMixin {
    @WrapMethod(method = "postProcessGeneration")
    private void avilixlogger$generation(Operation<Void> original) {
        if (!LoggerConfig.isEnabled()) { original.call(); return; }
        try (var scope = MutationSourceResolver.push(MutationSourceResolver.VANILLA_SIMULATION)) {
            original.call();
        }
    }
}
