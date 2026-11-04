package com.roften.avilixlogger.compat.backpacked.mixin;
import com.mrcrayfish.backpacked.inventory.ManagementInventory;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ManagementInventory.class)
public interface BackpackTrackedContainerAccessor {
    @Accessor("player") ServerPlayer avilixlogger$player();
}
