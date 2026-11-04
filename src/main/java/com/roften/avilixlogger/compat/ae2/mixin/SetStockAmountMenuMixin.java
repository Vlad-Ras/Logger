package com.roften.avilixlogger.compat.ae2.mixin;

import appeng.api.stacks.GenericStack;
import appeng.helpers.InterfaceLogicHost;
import appeng.menu.AEBaseMenu;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

/** Interface stock quantities live in the host config, not in this short-lived submenu's slots. */
@Mixin(targets = "appeng.menu.implementations.SetStockAmountMenu", remap = false)
public abstract class SetStockAmountMenuMixin {
    @Shadow @Final private InterfaceLogicHost host;
    @Shadow private int slot;
    @WrapMethod(method = "confirm")
    private void avilixlogger$confirm(int amount, Operation<Void> original) {
        AEBaseMenu menu = (AEBaseMenu) (Object) this;
        if (!Ae2Audit.enabled() || !Ae2Audit.onServerThread(menu)) { original.call(amount); return; }
        ItemStack before = avilixlogger$snapshot();
        try { original.call(amount); }
        finally {
            if (before != null && Ae2Audit.enabled()) {
                try {
                    ItemStack after = avilixlogger$snapshot();
                    if (after != null) Ae2Audit.stockAmount(menu, slot, before, after);
                } catch (Throwable ignored) {
                    // An audit failure cannot replace the native packet result or exception.
                }
            }
        }
    }

    @Unique private ItemStack avilixlogger$snapshot() {
        try {
            GenericStack current = host.getConfig().getStack(slot);
            return current == null ? ItemStack.EMPTY : GenericStack.wrapInItemStack(current);
        } catch (Throwable ignored) {
            return null; // Unknown state must never masquerade as an empty stock filter.
        }
    }
}
