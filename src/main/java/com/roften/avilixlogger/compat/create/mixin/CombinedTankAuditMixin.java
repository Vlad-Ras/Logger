package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAutomationHooks;
import com.roften.avilixlogger.core.CartRestoreLocks;
import com.simibubi.create.foundation.fluid.CombinedTankWrapper;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;

/** Capture the composed capability before its child tanks mutate. */
@Mixin(value=CombinedTankWrapper.class,remap=false)
public abstract class CombinedTankAuditMixin {
    @WrapMethod(method="fill")
    private int avilixlogger$fill(FluidStack stack,IFluidHandler.FluidAction action,Operation<Integer> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return 0;
        var capture=CartAutomationHooks.fluid(this,action.simulate());boolean success=false;
        try{int result=original.call(stack,action);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainStack(FluidStack stack,IFluidHandler.FluidAction action,Operation<FluidStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return FluidStack.EMPTY;
        var capture=CartAutomationHooks.fluid(this,action.simulate());boolean success=false;
        try{var result=original.call(stack,action);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
    @WrapMethod(method="drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainAmount(int amount,IFluidHandler.FluidAction action,Operation<FluidStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return FluidStack.EMPTY;
        var capture=CartAutomationHooks.fluid(this,action.simulate());boolean success=false;
        try{var result=original.call(amount,action);success=true;return result;}finally{if(capture!=null)capture.finish(success);}
    }
}
