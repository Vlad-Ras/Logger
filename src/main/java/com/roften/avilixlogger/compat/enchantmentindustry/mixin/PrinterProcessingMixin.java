package com.roften.avilixlogger.compat.enchantmentindustry.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.*;
import com.roften.avilixlogger.compat.industry.*;

@Pseudo
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.fluids.printer.PrinterBlockEntity", remap = false)
public abstract class PrinterProcessingMixin {
    @WrapMethod(method = "onItemHeld(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;)Lcom/simibubi/create/content/kinetics/belt/behaviour/BeltProcessingBehaviour$ProcessingResult;", remap = false)
    @Coerce
    private Object avilixlogger$print(@Coerce Object transported, @Coerce Object handler, Operation<Object> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_PRINTERPROCESSINGMIXIN_PRINT.enabled()) { return original.call(transported, handler); }
        var action=IndustrialProcessing.print(this,transported); boolean success=false;
        try{var result=original.call(transported, handler);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "onItemHeld", at = @At(value = "INVOKE",
        target = "Lplus/dragons/createenchantmentindustry/common/fluids/printer/behaviour/PrintingBehaviour;getResult(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/neoforged/neoforge/fluids/FluidStack;)Lnet/minecraft/world/item/ItemStack;"), remap = false)
    private ItemStack avilixlogger$result(@Coerce Object printing,Level level,ItemStack input,net.neoforged.neoforge.fluids.FluidStack fluid,Operation<ItemStack> original){
        var result=original.call(printing,level,input,fluid);IndustrialProcessing.candidate(result);return result;
    }
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "onItemHeld", at = @At(value = "INVOKE",
        target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour;handleProcessingOnItem(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lcom/simibubi/create/content/kinetics/belt/behaviour/TransportedItemStackHandlerBehaviour$TransportedResult;)V"), remap = false)
    private void avilixlogger$installed(@Coerce Object handler,@Coerce Object transported,@Coerce Object result,Operation<Void> original){
        original.call(handler,transported,result);IndustrialProcessing.installed();
    }
}
