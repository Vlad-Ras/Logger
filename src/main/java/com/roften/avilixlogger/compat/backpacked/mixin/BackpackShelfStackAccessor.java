package com.roften.avilixlogger.compat.backpacked.mixin;

import com.mrcrayfish.backpacked.blockentity.ShelfBlockEntity;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ShelfBlockEntity.BackpackShelfContainer.class)
public interface BackpackShelfStackAccessor {
    @Accessor("stack") ItemStack avilixlogger$stack();
}
