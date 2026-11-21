package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserve due simulation ticks while a transaction owns their block; resume after unlock. */
@Mixin(ServerLevel.class)
public abstract class ScheduledRecoveryTickLockMixin {
    @Inject(method="tickBlock",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$deferBlock(BlockPos pos, Block block, CallbackInfo callback) {
        if (!CartAuditContext.rollbackActive) return;
        var level=(ServerLevel)(Object)this;
        if (CartRestoreLocks.blockLocked(level,pos)) {level.scheduleTick(pos,block,1);callback.cancel();}
    }
    @Inject(method="tickFluid",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$deferFluid(BlockPos pos, Fluid fluid, CallbackInfo callback) {
        if (!CartAuditContext.rollbackActive) return;
        var level=(ServerLevel)(Object)this;
        if (CartRestoreLocks.blockLocked(level,pos)) {level.scheduleTick(pos,fluid,1);callback.cancel();}
    }
}
