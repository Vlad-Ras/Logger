package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.stacks.GenericStack;
import appeng.helpers.InterfaceLogicHost;
import appeng.menu.AEBaseMenu;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Interface stock quantities live in the host config, not in this short-lived submenu's slots. */
@Mixin(targets = "appeng.menu.implementations.SetStockAmountMenu", remap = false)
public abstract class SetStockAmountMenuMixin {
    @Shadow @Final private InterfaceLogicHost host;
    @Shadow private int slot;
    @Unique private ItemStack avilixlogger$previous;

    @Inject(method = "confirm", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$before(int amount, CallbackInfo ci) {
        if (((AEBaseMenu) (Object) this).getPlayer() instanceof ServerPlayer) {
            avilixlogger$previous = snapshot();
        }
    }

    @Inject(method = "confirm", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$after(int amount, CallbackInfo ci) {
        ItemStack before = avilixlogger$previous;
        avilixlogger$previous = null;
        if (before != null) {
            try {
                Ae2Audit.stockAmount((AEBaseMenu) (Object) this, slot, before, snapshot());
            } catch (Throwable ignored) {
                // Stock amount still changes even if the audit backend is unavailable.
            }
    }

    private ItemStack snapshot() {
        try {
            GenericStack current = host.getInterfaceLogic().getConfig().getStack(slot);
            return current == null ? ItemStack.EMPTY : GenericStack.wrapInItemStack(current);
        } catch (Throwable ignored) {
            return ItemStack.EMPTY;
        }
    }
}
