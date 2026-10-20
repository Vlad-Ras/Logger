package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class CartRecipientLogoutMixin {
    @Inject(method="remove",at=@At("HEAD"),require=1)
    private void avilixlogger$beforeSave(ServerPlayer player,CallbackInfo ci){
        if(CartRestoreLocks.playerLocked(player))com.roften.avilixlogger.compat.create.CartRollbackCoordinator.playerLeaving(player);
    }
}
