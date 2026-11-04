package com.roften.avilixlogger.compat.create.mixin;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.roften.avilixlogger.compat.create.CreateDeployerTransaction;
import net.minecraft.server.PlayerAdvancements;
import net.minecraft.advancements.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
@Mixin(PlayerAdvancements.class)
public abstract class PlayerProgressReservationMixin {
    @Inject(method={"award","revoke"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$lock(AdvancementHolder holder,String criterion,CallbackInfoReturnable<Boolean> ci){
        var player=((PlayerProgressAccessor)this).avilixlogger$player();
        if(CartRestoreLocks.handlerLocked(this)||CartRestoreLocks.playerLocked(player)){ci.setReturnValue(false);return;}
        // Functions may issue arbitrary server commands, outside any sparse endpoint contract.
        if(holder.value().rewards().function().isPresent())CreateDeployerTransaction.unsupported("Награда достижения выполняет произвольную командную функцию");
    }
}
