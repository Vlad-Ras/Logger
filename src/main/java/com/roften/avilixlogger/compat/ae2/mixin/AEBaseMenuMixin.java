package com.roften.avilixlogger.compat.ae2.mixin;

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
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

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

    @Inject(method = "clicked", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$clickBefore(int slot, int button, ClickType kind, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) avilixlogger$beforeAction();
    }

    @Inject(method = "clicked", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$clickAfter(int slot, int button, ClickType kind, Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer) avilixlogger$afterAction();
    }

    @Inject(method = "quickMoveStack", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$moveBefore(Player player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (player instanceof ServerPlayer) avilixlogger$beforeAction();
    }

    @Inject(method = "quickMoveStack", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$moveAfter(Player player, int slot, CallbackInfoReturnable<ItemStack> cir) {
        if (player instanceof ServerPlayer) avilixlogger$afterAction();
    }

    @Inject(method = "doAction", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$actionBefore(ServerPlayer player, InventoryAction action, int slot, long id, CallbackInfo ci) {
        avilixlogger$beforeAction();
    }

    @Inject(method = "doAction", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$actionAfter(ServerPlayer player, InventoryAction action, int slot, long id, CallbackInfo ci) {
        avilixlogger$afterAction();
    }

    @Inject(method = "setFilter", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$filterBefore(int slot, ItemStack item, CallbackInfo ci) {
        avilixlogger$beforeAction();
    }

    @Inject(method = "setFilter", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$filterAfter(int slot, ItemStack item, CallbackInfo ci) {
        avilixlogger$afterAction();
    }

    @Inject(method = "receiveClientAction", at = @At("HEAD"), require = 0, remap = false)
    private void avilixlogger$clientActionBefore(String name, String json, CallbackInfo ci) {
        avilixlogger$beforeAction();
    }

    @Inject(method = "receiveClientAction", at = @At("RETURN"), require = 0, remap = false)
    private void avilixlogger$clientActionAfter(String name, String json, CallbackInfo ci) {
        avilixlogger$afterAction();
    }
}
