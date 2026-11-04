package com.roften.avilixlogger.compat.create.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@Mixin(value=com.simibubi.create.foundation.blockEntity.ItemHandlerContainer.class,remap=false)
public interface CartMenuContainerAccessor {
    @Accessor("inv") IItemHandlerModifiable avilixlogger$handler();
}
