package com.roften.avilixlogger.compat.create.mixin;

import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=CombinedTankWrapper.class,remap=false)
public interface CombinedTankAccessor {
    @Accessor("itemHandler") IFluidHandler[] avilixlogger$children();
}
