package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.core.CauseContext;
import com.roften.avilixlogger.core.MutationSourceResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The conversion is a fluid reaction, even if another player's action caused a neighbor update. */
@Mixin(LiquidBlock.class)
public abstract class LiquidReactionSourceMixin {
    @WrapOperation(method={"onPlace","neighborChanged"}, at=@At(value="INVOKE",
            target="Lnet/neoforged/neoforge/fluids/FluidInteractionRegistry;canInteract(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)Z", remap=false), require=2)
    private boolean avilixlogger$registryReaction(Level level, BlockPos pos, Operation<Boolean> original) {
        String previous=MutationSourceResolver.enter("system:fluid_reaction");
        try(var scope=CauseContext.unattributed()){return original.call(level,pos);}
        finally{MutationSourceResolver.restore(previous);}
    }

    @WrapOperation(method="shouldSpreadLiquid", at=@At(value="INVOKE",
            target="Lnet/minecraft/world/level/Level;setBlockAndUpdate(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;)Z"), require=1)
    private boolean avilixlogger$reaction(Level level, BlockPos pos, BlockState state, Operation<Boolean> original) {
        String previous=MutationSourceResolver.enter("minecraft:fluid_reaction");
        try(var scope=CauseContext.unattributed()){return original.call(level,pos,state);}
        finally{MutationSourceResolver.restore(previous);}
    }
}
