package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
import com.roften.avilixlogger.core.CartAutomationHooks;
import com.roften.avilixlogger.core.CartRestoreLocks;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import org.spongepowered.asm.mixin.Mixin;

/** World creative tank: capture the actual fill/drain result, not its unchanged template. */
@Mixin(targets="com.simibubi.create.content.fluids.tank.CreativeFluidTankBlockEntity$CreativeSmartFluidTank",remap=false)
public abstract class WorldCreativeTankAuditMixin {
    @WrapMethod(method="fill")
    private int avilixlogger$fill(FluidStack input,IFluidHandler.FluidAction action,Operation<Integer> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return 0;
        if(action.simulate()||!CartAutomationAudit.worldCreativeActive(this))return original.call(input,action);
        FluidStack template;
        try{template=((IFluidHandler)this).getFluidInTank(0).copy();}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();return original.call(input,action);}
        FluidStack offered=input.copy();
        int accepted;
        try{accepted=original.call(input,action);}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();throw error;}
        if(accepted<0||accepted>offered.getAmount())CartAutomationAudit.worldCreativeUnverified();
        else if(accepted>0)CartAutomationAudit.worldCreativeFluid(this,template,offered.copyWithAmount(accepted),true);
        return accepted;
    }

    @WrapMethod(method="drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainStack(FluidStack request,IFluidHandler.FluidAction action,
                                                Operation<FluidStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return FluidStack.EMPTY;
        if(action.simulate()||!CartAutomationAudit.worldCreativeActive(this))return original.call(request,action);
        FluidStack template;
        try{template=((IFluidHandler)this).getFluidInTank(0).copy();}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();return original.call(request,action);}
        FluidStack result;
        try{result=original.call(request,action);}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();throw error;}
        if(result==null)CartAutomationAudit.worldCreativeUnverified();
        else if(!result.isEmpty()&&result.getAmount()>request.getAmount())CartAutomationAudit.worldCreativeUnverified();
        else if(!result.isEmpty())CartAutomationAudit.worldCreativeFluid(this,template,result.copy(),false);
        return result;
    }

    @WrapMethod(method="drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;")
    private FluidStack avilixlogger$drainAmount(int amount,IFluidHandler.FluidAction action,
                                                 Operation<FluidStack> original){
        if(CartRestoreLocks.handlerLocked(this)||CartAutomationHooks.locked(this))return FluidStack.EMPTY;
        if(action.simulate()||!CartAutomationAudit.worldCreativeActive(this))return original.call(amount,action);
        FluidStack template;
        try{template=((IFluidHandler)this).getFluidInTank(0).copy();}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();return original.call(amount,action);}
        FluidStack result;
        try{result=original.call(amount,action);}
        catch(RuntimeException error){CartAutomationAudit.worldCreativeUnverified();throw error;}
        if(result==null)CartAutomationAudit.worldCreativeUnverified();
        else if(!result.isEmpty()&&result.getAmount()>amount)CartAutomationAudit.worldCreativeUnverified();
        else if(!result.isEmpty())CartAutomationAudit.worldCreativeFluid(this,template,result.copy(),false);
        return result;
    }
}
