package com.roften.avilixlogger.compat.create;

import com.roften.avilixlogger.core.RecoveryFluidBalance;
import com.simibubi.create.content.fluids.tank.FluidTankBlockEntity;

/** Read on the server thread once; the resource worker receives only primitive configuration. */
public final class CreateFluidRecovery {
    private CreateFluidRecovery() {}
    public static RecoveryFluidBalance.Limits limits(){return new RecoveryFluidBalance.Limits(FluidTankBlockEntity.getCapacityMultiplier(),FluidTankBlockEntity.getMaxHeight());}
    public static net.minecraft.core.BlockPos controller(net.minecraft.world.level.block.entity.BlockEntity be) {
        return be instanceof FluidTankBlockEntity tank?(tank.isController()?tank.getBlockPos():tank.getController()):null;
    }
}
