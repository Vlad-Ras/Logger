package com.roften.avilixlogger.compat.chipped.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.chipped.ChippedCraftAudit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;

@Pseudo
@Mixin(targets="earth.terrarium.chipped.common.menus.WorkbenchMenu",remap=false)
public abstract class WorkbenchMenuMixin {
    @Shadow @Final protected Inventory inventory;
    @WrapMethod(method="craft(Lnet/minecraft/world/item/ItemStack;Z)V",remap=false)
    private void avilixlogger$craft(ItemStack requested,boolean all,Operation<Void> original) {
        var action=inventory.player instanceof ServerPlayer player?ChippedCraftAudit.begin(player,requested,all):null;
        try { original.call(requested,all); } finally { if(action!=null)action.finish(); }
    }
}
