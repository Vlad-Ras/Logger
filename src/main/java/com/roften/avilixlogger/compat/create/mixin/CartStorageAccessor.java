package com.roften.avilixlogger.compat.create.mixin;

import com.simibubi.create.content.contraptions.MountedStorageManager;
import com.simibubi.create.api.contraption.storage.item.MountedItemStorage;
import com.simibubi.create.api.contraption.storage.fluid.MountedFluidStorage;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = MountedStorageManager.class, remap = false)
public interface CartStorageAccessor {
    @Invoker("reset") void avilixlogger$reset();
    @Invoker("addStorage") void avilixlogger$addItem(MountedItemStorage storage, BlockPos pos);
    @Invoker("addStorage") void avilixlogger$addFluid(MountedFluidStorage storage, BlockPos pos);
}
