package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.MutationSourceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Distinguishes vanilla simulation from mod block callbacks without inspecting call stacks. */
@Mixin(ServerLevel.class)
public abstract class SimulationSourceMixin {
    @WrapMethod(method = "tickBlock")
    private void avilixlogger$scheduled(BlockPos pos, Block block, Operation<Void> original) {
        if (!LoggerConfig.isEnabled()) { original.call(pos, block); return; }
        String source = MutationSourceResolver.sourceFor(block);
        String previous = MutationSourceResolver.enter(source == null ? MutationSourceResolver.VANILLA_SIMULATION : source);
        try { original.call(pos, block); } finally { MutationSourceResolver.restore(previous); }
    }

    @WrapMethod(method = "tickFluid")
    private void avilixlogger$fluid(BlockPos pos, Fluid fluid, Operation<Void> original) {
        if (!LoggerConfig.isEnabled()) { original.call(pos, fluid); return; }
        String source = MutationSourceResolver.sourceFor(fluid);
        String previous = MutationSourceResolver.enter(source == null ? MutationSourceResolver.VANILLA_SIMULATION : source);
        try { original.call(pos, fluid); } finally { MutationSourceResolver.restore(previous); }
    }

    @WrapOperation(method = "tickChunk", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;randomTick(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"))
    private void avilixlogger$random(BlockState state, ServerLevel level, BlockPos pos, RandomSource random,
                                     Operation<Void> original) {
        if (!LoggerConfig.isEnabled()) { original.call(state, level, pos, random); return; }
        String source = MutationSourceResolver.sourceFor(state.getBlock());
        String previous = MutationSourceResolver.enter(source == null ? MutationSourceResolver.VANILLA_SIMULATION : source);
        try { original.call(state, level, pos, random); } finally { MutationSourceResolver.restore(previous); }
    }
}
