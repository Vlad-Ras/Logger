package com.roften.avilixlogger.compat.dieselgenerators.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.industry.IndustrialPlayers;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.*;
@Pseudo
@Mixin(targets="com.jesz.createdieselgenerators.content.tools.FueledToolItem",remap=false)
public interface FueledItemMixin {
    @WrapMethod(method="writeFluid(Lnet/minecraft/world/item/ItemStack;Lnet/neoforged/neoforge/fluids/FluidStack;)V",remap=false)
    private void avilixlogger$write(ItemStack stack,FluidStack fluid,Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_FUELEDITEMMIXIN_WRITE.enabled()) { original.call(stack,fluid); return; }
        var action=IndustrialPlayers.stack(stack,null);boolean success=false;
        try{original.call(stack,fluid);success=true;}finally{if(action!=null)action.finish(!success);}
    }
}
