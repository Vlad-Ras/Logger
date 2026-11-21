package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.core.CartRestoreLocks;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Explosion drops happen before setBlock; filter reserved positions before either operation. */
@Mixin(Explosion.class)
public abstract class ExplosionRecoveryLockMixin {
    @Shadow @Final private Level level;
    @Shadow @Final private ObjectArrayList<BlockPos> toBlow;
    @Inject(method="finalizeExplosion",at=@At("HEAD"),require=1)
    private void avilixlogger$protectReserved(boolean particles, CallbackInfo callback) {
        if (!CartAuditContext.rollbackActive || level.isClientSide() || CartAuditContext.restoring()) return;
        toBlow.removeIf(pos->CartRestoreLocks.blockLocked(level,pos));
    }
}
