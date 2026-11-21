package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.RecoveryEntityReservations;
import net.minecraft.world.level.entity.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PersistentEntitySectionManager.class)
public abstract class RecoveryEntityUnloadLockMixin {
    @Inject(method="processChunkUnload",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$saveWithoutUnloading(long chunk,CallbackInfoReturnable<Boolean> result) {
        if(!RecoveryEntityReservations.heldChunk(this,chunk))return;
        // Returning false would deadlock saveAll's retry loop. Save normally but retain the
        // section and its load status until the scene releases this UUID/chunk barrier.
        @SuppressWarnings("unchecked") var access=(RecoveryEntityManagerAccessor<EntityAccess>)(Object)this;
        result.setReturnValue(access.avilixlogger$storeReservedChunk(chunk,ignored->{}));
    }
}
