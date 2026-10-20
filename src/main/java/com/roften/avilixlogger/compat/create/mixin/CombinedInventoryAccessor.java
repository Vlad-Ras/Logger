package com.roften.avilixlogger.compat.create.mixin;

import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.CombinedInvWrapper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=CombinedInvWrapper.class,remap=false)
public interface CombinedInventoryAccessor {
    @Accessor("itemHandler") IItemHandlerModifiable[] avilixlogger$children();
}
