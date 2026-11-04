package com.roften.avilixlogger.compat.create.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CreateStructuralAudit;
import com.simibubi.create.content.contraptions.bearing.*;
import com.simibubi.create.content.contraptions.piston.MechanicalPistonBlockEntity;
import com.simibubi.create.content.contraptions.pulley.PulleyBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(value={MechanicalBearingBlockEntity.class,ClockworkBearingBlockEntity.class,MechanicalPistonBlockEntity.class,PulleyBlockEntity.class,ElevatorPulleyBlockEntity.class},remap=false)
public abstract class ControllerDisassemblyAuditMixin {
    @WrapMethod(method="assemble")
    private void avilixlogger$assemblyLocked(Operation<Void> original){
        if(!CreateStructuralAudit.controllerLocked((BlockEntity)(Object)this))original.call();
    }
    @WrapMethod(method="disassemble")
    private void avilixlogger$disassemble(Operation<Void> original){
        var self=(BlockEntity)(Object)this;if(CreateStructuralAudit.controllerLocked(self))return;
        try(var scope=CreateStructuralAudit.begin(self)){original.call();if(scope!=null)scope.success();}
    }
}
