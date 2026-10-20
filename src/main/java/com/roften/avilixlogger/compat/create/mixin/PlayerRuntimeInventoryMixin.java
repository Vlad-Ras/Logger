package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.CreateLivingRuntime;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Expanded snapshots are scoped to runtime capture; regular player saves use vanilla. */
@Mixin(Player.class)
public abstract class PlayerRuntimeInventoryMixin {
    @WrapOperation(method="addAdditionalSaveData",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Inventory;save(Lnet/minecraft/nbt/ListTag;)Lnet/minecraft/nbt/ListTag;"),require=1)
    private ListTag avilixlogger$inventory(Inventory inventory,ListTag values,Operation<ListTag> original){
        var player=(Player)(Object)this;
        return CreateLivingRuntime.savingInventory(player)?CreateLivingRuntime.inventorySnapshot(player):original.call(inventory,values);
    }
}
