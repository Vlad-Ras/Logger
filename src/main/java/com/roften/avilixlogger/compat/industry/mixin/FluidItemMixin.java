package com.roften.avilixlogger.compat.industry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.industry.IndustrialPlayers;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import org.spongepowered.asm.mixin.*;
@Mixin(targets="net.neoforged.neoforge.fluids.capability.templates.FluidHandlerItemStack",remap=false)
public abstract class FluidItemMixin {
    @WrapMethod(method="setFluid(Lnet/neoforged/neoforge/fluids/FluidStack;)V",remap=false)
    private void avilixlogger$set(FluidStack fluid,Operation<Void> original){
        if (!CaptureHandlers.INDUSTRY_FLUIDITEMMIXIN_SET.enabled()) { original.call(fluid); return; }
        var action=IndustrialPlayers.stack(((IFluidHandlerItem)this).getContainer(),null);boolean success=false;
        try{original.call(fluid);success=true;}finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="setContainerToEmpty()V",remap=false)
    private void avilixlogger$empty(Operation<Void> original){
        if (!CaptureHandlers.INDUSTRY_FLUIDITEMMIXIN_EMPTY.enabled()) { original.call(); return; }
        var action=IndustrialPlayers.stack(((IFluidHandlerItem)this).getContainer(),null);boolean success=false;
        try{original.call();success=true;}finally{if(action!=null)action.finish(!success);}
    }
}
