package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import com.roften.avilixlogger.compat.backpacked.BackpackDeployerGuard;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(SyncedDataKey.class)
public abstract class BackpackSyncedKeyReservationMixin implements BackpackDeployerGuard {
    @Inject(method="setValue",at=@At("HEAD"),cancellable=true)
    private void avilixlogger$reserved(Entity entity,Object value,CallbackInfo ci){if(entity instanceof Player player&&CartRestoreLocks.playerLocked(player))ci.cancel();}
}
