package com.roften.avilixlogger.compat.create.mixin;

import com.simibubi.create.foundation.blockEntity.behaviour.fluid.SmartFluidTankBehaviour;
import com.simibubi.create.foundation.fluid.SmartFluidTank;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value=SmartFluidTankBehaviour.TankSegment.class,remap=false)
public interface TankSegmentAccessor {
    @Accessor("tank") SmartFluidTank avilixlogger$tank();
}
