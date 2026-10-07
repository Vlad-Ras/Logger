package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartStorageAudit;
import com.simibubi.create.api.contraption.storage.fluid.WrapperMountedFluidStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;

/** Capture changed tanks only at real transfers, never on simulation or idle ticks. */
@Mixin(value=WrapperMountedFluidStorage.class,remap=false)
public abstract class MountedFluidStorageAuditMixin {
    @WrapMethod(method="fill")
    private int avilixlogger$fill(FluidStack stack,IFluidHandler.FluidAction action,Operation<Integer> original){
        var ref=action.simulate()?null:CartStorageAudit.ref(this);
        if(CartStorageAudit.locked(ref))return 0;
        if(ref==null)return original.call(stack,action);
        var handler=(IFluidHandler)this;var before=CartStorageAudit.fluids(handler);
        int result=original.call(stack,action);CartStorageAudit.changedFluids(ref,before,handler);return result;
    }
    @WrapMethod(method="drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainStack(FluidStack stack,IFluidHandler.FluidAction action,Operation<FluidStack> original){
        var ref=action.simulate()?null:CartStorageAudit.ref(this);
        if(CartStorageAudit.locked(ref))return FluidStack.EMPTY;
        if(ref==null)return original.call(stack,action);
        var handler=(IFluidHandler)this;var before=CartStorageAudit.fluids(handler);
        FluidStack result=original.call(stack,action);CartStorageAudit.changedFluids(ref,before,handler);return result;
    }
    @WrapMethod(method="drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainAmount(int amount,IFluidHandler.FluidAction action,Operation<FluidStack> original){
        var ref=action.simulate()?null:CartStorageAudit.ref(this);
        if(CartStorageAudit.locked(ref))return FluidStack.EMPTY;
        if(ref==null)return original.call(amount,action);
        var handler=(IFluidHandler)this;var before=CartStorageAudit.fluids(handler);
        FluidStack result=original.call(amount,action);CartStorageAudit.changedFluids(ref,before,handler);return result;
    }
}
