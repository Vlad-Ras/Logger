package com.roften.avilixlogger.smoke.mixin;

import com.roften.avilixlogger.smoke.FluidAttributionSmoke;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dev fixture only; observes actual setBlock invocations without enabling production logging. */
@Mixin(Level.class)
public abstract class FluidSetBlockProbeMixin {
    @Inject(method="setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at=@At("HEAD"), require=1)
    private void avilixlogger$observe(BlockPos pos,BlockState state,int flags,int recursionLeft,CallbackInfoReturnable<Boolean> ci){
        FluidAttributionSmoke.observe(pos,state);
    }
}
