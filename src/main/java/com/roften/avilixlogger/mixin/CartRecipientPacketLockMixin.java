package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class CartRecipientPacketLockMixin {
    @Shadow public ServerPlayer player;
    @Inject(method={"handleContainerClick","handleContainerClose","handleContainerButtonClick","handleSetCreativeModeSlot","handlePlayerAction","handleUseItem","handleUseItemOn","handleInteract","handleSetCarriedItem","handlePlaceRecipe","handleSelectTrade"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$inventoryAction(CallbackInfo ci){
        // The network-thread invocation must schedule its ordinary server-thread continuation.
        if(player.server.isSameThread() && CartRestoreLocks.playerLocked(player)){player.containerMenu.broadcastFullState();ci.cancel();}
    }
}
