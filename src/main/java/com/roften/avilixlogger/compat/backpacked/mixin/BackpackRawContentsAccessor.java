package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.container.UnlockableContainer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(UnlockableContainer.class)
public interface BackpackRawContentsAccessor {
    @Accessor("items") NonNullList<ItemStack> avilixlogger$items();
}
