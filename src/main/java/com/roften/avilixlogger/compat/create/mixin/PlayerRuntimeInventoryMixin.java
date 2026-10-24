package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.CreateLivingRuntime;
import com.roften.avilixlogger.compat.create.CreatePlayerRuntimeAccess;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expanded snapshots are scoped to runtime capture; regular player saves use vanilla. */
@Mixin(Player.class)
public abstract class PlayerRuntimeInventoryMixin implements CreatePlayerRuntimeAccess {
    @Accessor("enchantmentSeed")
    public abstract void avilixlogger$enchantmentSeed(int seed);
    @WrapOperation(method="addAdditionalSaveData",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Inventory;save(Lnet/minecraft/nbt/ListTag;)Lnet/minecraft/nbt/ListTag;"),require=1)
    private ListTag avilixlogger$inventory(Inventory inventory,ListTag values,Operation<ListTag> original){
        var player=(Player)(Object)this;
        return CreateLivingRuntime.savingInventory(player)?CreateLivingRuntime.inventorySnapshot(player):original.call(inventory,values);
    }
}
