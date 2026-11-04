package com.roften.avilixlogger.compat.create.mixin;

import com.simibubi.create.content.processing.basin.BasinBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=BasinBlockEntity.class,remap=false)
public interface BasinOutputTankAccessor {
    @Accessor("outputTank") SmartFluidTankBehaviour avilixlogger$outputTank();
}
