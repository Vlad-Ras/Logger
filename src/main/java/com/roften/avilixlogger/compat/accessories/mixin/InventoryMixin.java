package com.roften.avilixlogger.compat.accessories.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import com.roften.avilixlogger.compat.accessories.AccessoriesInventoryState;
import net.minecraft.nbt.*;
import net.minecraft.core.HolderLookup;
import java.util.*;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.ExpandedSimpleContainer", remap = false)
public abstract class InventoryMixin implements AccessoriesInventoryState {
    @Unique private Map<Integer, CompoundTag> avilixlogger$pending;
    public Map<Integer, CompoundTag> avilixlogger$acknowledged() {
        if (avilixlogger$pending == null) avilixlogger$pending = new HashMap<>();
        return avilixlogger$pending;
    }
    @WrapMethod(method = "setItem(ILnet/minecraft/world/item/ItemStack;)V", remap = false)
    private void avilixlogger$set(int slot, ItemStack stack, Operation<Void> original) {
        var action = AccessoriesAudit.inventory(this, "inventory-set"); boolean success = false;
        try { original.call(slot, stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeItem(II)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$remove(int slot, int amount, Operation<ItemStack> original) {
        var action = AccessoriesAudit.inventory(this, "inventory-remove"); boolean success = false;
        try { var result = original.call(slot, amount); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeItemNoUpdate(I)Lnet/minecraft/world/item/ItemStack;", remap = false)
    private ItemStack avilixlogger$removeWithoutUpdate(int slot, Operation<ItemStack> original) {
        var action = AccessoriesAudit.inventory(this, "inventory-removeWithoutUpdate"); boolean success = false;
        try { var result = original.call(slot); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "fromTag(Lnet/minecraft/nbt/ListTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$read(ListTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        var action = AccessoriesAudit.inventory(this, "inventory-read"); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setPreviousItem(ILnet/minecraft/world/item/ItemStack;)V", remap = false)
    private void avilixlogger$observe(int slot, ItemStack stack, Operation<Void> original) {
        var observation = AccessoriesAudit.observe(this, slot); boolean success = false;
        try { original.call(slot, stack); success = true; }
        finally { if (observation != null) observation.finish(!success); }
    }
}
