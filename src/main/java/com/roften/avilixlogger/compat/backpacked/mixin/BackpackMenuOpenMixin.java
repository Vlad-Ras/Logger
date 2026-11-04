package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.container.BackpackContainerMenu;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import com.roften.avilixlogger.core.ActionType;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.Container;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Mixin(BackpackContainerMenu.class)
public abstract class BackpackMenuOpenMixin {
    @Inject(method="<init>(ILnet/minecraft/world/entity/player/Inventory;Lnet/minecraft/world/Container;IIIIZLcom/mrcrayfish/backpacked/common/backpack/UnlockableSlots;Lcom/mrcrayfish/backpacked/common/Pagination;Lcom/mrcrayfish/backpacked/common/augment/Augments;Lcom/mrcrayfish/backpacked/common/backpack/UnlockableSlots;)V",at=@At("RETURN"))
    private void avilixlogger$opened(int id,Inventory inventory,Container bag,int ownerId,int index,int cols,int rows,boolean owner,com.mrcrayfish.backpacked.common.backpack.UnlockableSlots slots,com.mrcrayfish.backpacked.common.Pagination pagination,com.mrcrayfish.backpacked.common.augment.Augments augments,com.mrcrayfish.backpacked.common.backpack.UnlockableSlots bays,CallbackInfo ci){
        if(inventory.player instanceof ServerPlayer p){var ref=BackpackAudit.ref(bag);if(ref!=null)BackpackAudit.lifecycle(ref,p,ActionType.BACKPACK_OPEN,owner?"owner_open":"pickpocket_open");}
    }
    @Inject(method="removed",at=@At("HEAD"))
    private void avilixlogger$closed(Player player,CallbackInfo ci){if(player instanceof ServerPlayer p){var menu=(BackpackContainerMenu)(Object)this;var ref=BackpackAudit.ref(menu.getBackpackInventory());if(ref!=null)BackpackAudit.lifecycle(ref,p,ActionType.BACKPACK_CLOSE,"menu_close");}}
}
