package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value=FluidTank.class,remap=false)
public abstract class CartFluidHandlerLockMixin {
    @WrapMethod(method="fill")
    private int avilixlogger$fill(FluidStack stack,IFluidHandler.FluidAction action,Operation<Integer> original){return CartRestoreLocks.handlerLocked(this)?0:original.call(stack,action);}
    @WrapMethod(method="drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainStack(FluidStack stack,IFluidHandler.FluidAction action,Operation<FluidStack> original){return CartRestoreLocks.handlerLocked(this)?FluidStack.EMPTY:original.call(stack,action);}
    @WrapMethod(method="drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainAmount(int amount,IFluidHandler.FluidAction action,Operation<FluidStack> original){return CartRestoreLocks.handlerLocked(this)?FluidStack.EMPTY:original.call(amount,action);}
    @WrapMethod(method="setFluid")
    private void avilixlogger$set(FluidStack stack,Operation<Void> original){if(!CartRestoreLocks.handlerLocked(this))original.call(stack);}
}
