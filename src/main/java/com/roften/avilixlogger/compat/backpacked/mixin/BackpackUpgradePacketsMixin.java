package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mrcrayfish.backpacked.network.play.ServerPlayHandler;
import com.mrcrayfish.framework.api.network.MessageContext;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Coerce;
@Mixin(ServerPlayHandler.class)
public abstract class BackpackUpgradePacketsMixin {
    @WrapMethod(method={"handleChangeAugment","handleSetAugmentState","handleUpdateAugment","handleUnlockSlot","handleUnlockAugmentBay","handleRenameBackpack","handleCustomiseBackpack","handleSortBackpack"})
    private static void avilixlogger$setting(@Coerce Object message,MessageContext context,Operation<Void> original){
        try(var scope=BackpackAudit.scope(context.getPlayer().orElse(null),"setting:"+message.getClass().getSimpleName())){original.call(message,context);}
    }
}
