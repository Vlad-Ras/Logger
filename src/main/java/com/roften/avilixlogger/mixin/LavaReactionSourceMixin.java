package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.core.CauseContext;
import com.roften.avilixlogger.core.MutationSourceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.LavaFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Flowing lava falling onto water creates stone independently of the surrounding player scope. */
@Mixin(LavaFluid.class)
public abstract class LavaReactionSourceMixin {
    @WrapOperation(method="spreadTo", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/LevelAccessor;setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;I)Z"), require=1)
    private boolean avilixlogger$reaction(LevelAccessor level, BlockPos pos, BlockState state, int flags,
                                            Operation<Boolean> original) {
        String previous=MutationSourceResolver.enter("minecraft:fluid_reaction");
        try(var scope=CauseContext.unattributed()){return original.call(level,pos,state,flags);}
        finally{MutationSourceResolver.restore(previous);}
    }
}
