package com.roften.avilixlogger.compat.ae2.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import appeng.helpers.InventoryAction;
import appeng.menu.AEBaseMenu;
import com.roften.avilixlogger.compat.ae2.Ae2Audit;
import com.roften.avilixlogger.compat.ae2.Ae2MenuCapture;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Captures changes synchronously around player actions, never during an idle open menu tick. */
@Mixin(targets = "appeng.menu.AEBaseMenu", remap = false)
public abstract class AEBaseMenuMixin implements Ae2MenuCapture {
    @Unique private int avilixlogger$depth;
    @Unique private Ae2Audit.Snapshot avilixlogger$before;

    @Override
    public void avilixlogger$beforeAction() {
        if (avilixlogger$depth++ == 0) {
            try {
                avilixlogger$before = Ae2Audit.capture((AEBaseMenu) (Object) this);
            } catch (Throwable ignored) {
                avilixlogger$before = null;
            }
        }
    }

    @Override
    public void avilixlogger$afterAction() {
        if (avilixlogger$depth <= 0) return;
        if (--avilixlogger$depth == 0) {
            Ae2Audit.Snapshot snapshot = avilixlogger$before;
            avilixlogger$before = null;
            try {
                Ae2Audit.compare((AEBaseMenu) (Object) this, snapshot);
            } catch (Throwable ignored) {
                // A logger failure must not reject the original AE2 inventory packet.
            }
        }
    }

    @WrapMethod(method = "clicked")
    private void avilixlogger$click(int slot, int button, ClickType kind, Player player,
            Operation<Void> original) {
        if (!(player instanceof ServerPlayer)) { original.call(slot, button, kind, player); return; }
        avilixlogger$beforeAction();
        try { original.call(slot, button, kind, player); }
        finally { avilixlogger$afterAction(); }
    }

    @WrapMethod(method = "quickMoveStack")
    private ItemStack avilixlogger$move(Player player, int slot,
            Operation<ItemStack> original) {
        if (!(player instanceof ServerPlayer)) return original.call(player, slot);
        avilixlogger$beforeAction();
        try { return original.call(player, slot); }
        finally { avilixlogger$afterAction(); }
    }

    @WrapMethod(method = "doAction")
    private void avilixlogger$action(ServerPlayer player, InventoryAction action, int slot, long id,
            Operation<Void> original) {
        avilixlogger$beforeAction();
        try { original.call(player, action, slot, id); }
        finally { avilixlogger$afterAction(); }
    }

    @WrapMethod(method = "setFilter")
    private void avilixlogger$filter(int slot, ItemStack item,
            Operation<Void> original) {
        avilixlogger$beforeAction();
        try { original.call(slot, item); }
        finally { avilixlogger$afterAction(); }
    }

    @WrapMethod(method = "receiveClientAction")
    private void avilixlogger$clientAction(String name, String json,
            Operation<Void> original) {
        avilixlogger$beforeAction();
        try { original.call(name, json); }
        finally { avilixlogger$afterAction(); }
    }
}
