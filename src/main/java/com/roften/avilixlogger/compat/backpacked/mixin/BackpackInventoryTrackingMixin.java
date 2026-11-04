package com.roften.avilixlogger.compat.backpacked.mixin;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(Inventory.class)
public abstract class BackpackInventoryTrackingMixin {
    @Shadow public Player player;
    @Inject(method="setChanged",at=@At("RETURN"))
    private void avilixlogger$inventory(CallbackInfo ci){if(player instanceof ServerPlayer p)BackpackAudit.trackPlayer(p,ActionType.BACKPACK_TRANSFER);}
}
