package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.roften.avilixlogger.compat.create.CreateDeployerTransaction;
import com.simibubi.create.content.equipment.sandPaper.SandPaperItem;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Capture the loose input before native sandpaper splits or discards it. */
@Mixin(value=SandPaperItem.class,remap=false)
public abstract class SandPaperWorldItemAuditMixin {
    @WrapOperation(method="use",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/item/ItemEntity;getItem()Lnet/minecraft/world/item/ItemStack;"),require=1)
    private ItemStack avilixlogger$input(ItemEntity entity,Operation<ItemStack> original){
        CreateDeployerTransaction.entityBefore(entity);
        return original.call(entity);
    }
}
