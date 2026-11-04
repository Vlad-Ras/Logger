package com.roften.avilixlogger.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

@Mixin(value=net.neoforged.neoforge.items.wrapper.CombinedInvWrapper.class,remap=false)
public interface CartCombinedInventoryAccessor {
    @Invoker("getIndexForSlot") int avilixlogger$index(int slot);
    @Invoker("getHandlerFromIndex") IItemHandlerModifiable avilixlogger$handler(int index);
    @Invoker("getSlotFromIndex") int avilixlogger$slot(int slot,int index);
}
