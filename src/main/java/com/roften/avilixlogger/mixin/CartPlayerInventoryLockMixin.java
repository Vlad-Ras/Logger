package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.CartRestoreLocks;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.*;

/** Reserve native recipient inventories while a linked undo spans multiple ticks. */
@Mixin(Inventory.class)
public abstract class CartPlayerInventoryLockMixin {
    @Shadow @Final public Player player;
    @Inject(method={"setItem","clearContent","dropAll","replaceWith","placeItemBackInInventory","removeItem(Lnet/minecraft/world/item/ItemStack;)V","load","pickSlot","setPickedItem","swapPaint","tick"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$write(CallbackInfo ci){if(CartRestoreLocks.playerLocked(player))ci.cancel();}
    @Inject(method={"removeItem(II)Lnet/minecraft/world/item/ItemStack;","removeItemNoUpdate","removeFromSelected"},at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$remove(CallbackInfoReturnable<ItemStack> ci){if(CartRestoreLocks.playerLocked(player))ci.setReturnValue(ItemStack.EMPTY);}
    @Inject(method="add",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$add(CallbackInfoReturnable<Boolean> ci){if(CartRestoreLocks.playerLocked(player))ci.setReturnValue(false);}
    @Inject(method="clearOrCountMatchingItems",at=@At("HEAD"),cancellable=true,require=1)
    private void avilixlogger$clear(CallbackInfoReturnable<Integer> ci){if(CartRestoreLocks.playerLocked(player))ci.setReturnValue(0);}
}
