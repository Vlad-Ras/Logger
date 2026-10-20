package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.BackpackInventory;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(BackpackInventory.class)
public interface BackpackInventoryAccessor {
    @Accessor("player") Player avilixlogger$player();
    @Accessor("index") int avilixlogger$index();
}
