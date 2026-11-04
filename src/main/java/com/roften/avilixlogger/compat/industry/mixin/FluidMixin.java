package com.roften.avilixlogger.compat.industry.mixin;
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
@Mixin(targets = "net.neoforged.neoforge.fluids.capability.templates.FluidTank", remap = false)
public abstract class FluidMixin {
    @WrapMethod(method = "fill(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)I", remap = false)
    private int avilixlogger$fill(net.neoforged.neoforge.fluids.FluidStack stack, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction mode, Operation<Integer> original) {
        var action = IndustrialFluids.begin(this, mode.simulate()); boolean success = false;
        try { var result = original.call(stack, mode); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "drain(Lnet/neoforged/neoforge/fluids/FluidStack;Lnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;", remap = false)
    private net.neoforged.neoforge.fluids.FluidStack avilixlogger$drainFluid(net.neoforged.neoforge.fluids.FluidStack stack, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction mode, Operation<net.neoforged.neoforge.fluids.FluidStack> original) {
        var action = IndustrialFluids.begin(this, mode.simulate()); boolean success = false;
        try { var result = original.call(stack, mode); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "drain(ILnet/neoforged/neoforge/fluids/capability/IFluidHandler$FluidAction;)Lnet/neoforged/neoforge/fluids/FluidStack;", remap = false)
    private net.neoforged.neoforge.fluids.FluidStack avilixlogger$drainAmount(int amount, net.neoforged.neoforge.fluids.capability.IFluidHandler.FluidAction mode, Operation<net.neoforged.neoforge.fluids.FluidStack> original) {
        var action = IndustrialFluids.begin(this, mode.simulate()); boolean success = false;
        try { var result = original.call(amount, mode); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setFluid(Lnet/neoforged/neoforge/fluids/FluidStack;)V", remap = false)
    private void avilixlogger$set(net.neoforged.neoforge.fluids.FluidStack stack, Operation<Void> original) {
        var action = IndustrialFluids.begin(this, false); boolean success = false;
        try { original.call(stack); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setCapacity(I)Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;", remap = false)
    private net.neoforged.neoforge.fluids.capability.templates.FluidTank avilixlogger$capacity(int capacity, Operation<net.neoforged.neoforge.fluids.capability.templates.FluidTank> original) {
        var action = IndustrialFluids.begin(this, false); boolean success = false;
        try { var result = original.call(capacity); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "readFromNBT(Lnet/minecraft/core/HolderLookup$Provider;Lnet/minecraft/nbt/CompoundTag;)Lnet/neoforged/neoforge/fluids/capability/templates/FluidTank;", remap = false)
    private net.neoforged.neoforge.fluids.capability.templates.FluidTank avilixlogger$read(HolderLookup.Provider provider, CompoundTag tag, Operation<net.neoforged.neoforge.fluids.capability.templates.FluidTank> original) {
        var action = IndustrialFluids.begin(this, false); boolean success = false;
        try { var result = original.call(provider, tag); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
