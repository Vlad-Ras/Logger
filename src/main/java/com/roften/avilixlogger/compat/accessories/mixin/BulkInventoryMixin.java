package com.roften.avilixlogger.compat.accessories.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import com.roften.avilixlogger.compat.accessories.AccessoriesInventoryState;
import java.util.List;

/** Inherited bulk operations can mutate the backing list without ExpandedSimpleContainer.setItem. */
@Mixin(SimpleContainer.class)
public abstract class BulkInventoryMixin {
    @WrapMethod(method = "clearContent")
    private void avilixlogger$clearContent(Operation<Void> original) {
        if (!CaptureHandlers.ACCESSORIES_BULKINVENTORYMIXIN_CLEARCONTENT.enabled()) { original.call(); return; }
        var action = (Object)this instanceof AccessoriesInventoryState ? AccessoriesAudit.inventory(this, "inventory-clearContent") : null;
        boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeAllItems")
    private List<ItemStack> avilixlogger$removeAllItems(Operation<List<ItemStack>> original) {
        if (!CaptureHandlers.ACCESSORIES_BULKINVENTORYMIXIN_REMOVEALLITEMS.enabled()) { return original.call(); }
        var action = (Object)this instanceof AccessoriesInventoryState ? AccessoriesAudit.inventory(this, "inventory-removeAllItems") : null;
        boolean success = false;
        try { var result = original.call(); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addItem")
    private ItemStack avilixlogger$addItem(ItemStack stack, Operation<ItemStack> original) {
        if (!CaptureHandlers.ACCESSORIES_BULKINVENTORYMIXIN_ADDITEM.enabled()) { return original.call(stack); }
        var action = (Object)this instanceof AccessoriesInventoryState ? AccessoriesAudit.inventory(this, "inventory-addItem") : null;
        boolean success = false;
        try { var result = original.call(stack); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeItemType")
    private ItemStack avilixlogger$removeItemType(Item item, int count, Operation<ItemStack> original) {
        if (!CaptureHandlers.ACCESSORIES_BULKINVENTORYMIXIN_REMOVEITEMTYPE.enabled()) { return original.call(item, count); }
        var action = (Object)this instanceof AccessoriesInventoryState ? AccessoriesAudit.inventory(this, "inventory-removeItemType") : null;
        boolean success = false;
        try { var result = original.call(item, count); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
