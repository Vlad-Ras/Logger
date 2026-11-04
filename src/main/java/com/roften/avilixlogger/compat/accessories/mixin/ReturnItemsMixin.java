package com.roften.avilixlogger.compat.accessories.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.AccessoriesInternals", remap = false)
public abstract class ReturnItemsMixin {
    @WrapMethod(method = "giveItemToPlayer(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V", remap = false)
    private static void avilixlogger$return(ServerPlayer player, ItemStack stack, Operation<Void> original) {
        var action = AccessoriesAudit.returned(player, stack); boolean success = false;
        try { original.call(player, stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
