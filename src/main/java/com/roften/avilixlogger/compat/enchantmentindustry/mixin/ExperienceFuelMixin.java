package com.roften.avilixlogger.compat.enchantmentindustry.mixin;
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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.fluids.experience.BlazeExperienceBlockEntity", remap = false)
public abstract class ExperienceFuelMixin {
    @WrapMethod(method = "applyExperienceFuel(Lplus/dragons/createenchantmentindustry/common/fluids/experience/ExperienceFuel;ZZ)Z", remap = false)
    private boolean avilixlogger$fuel(@Coerce Object fuel, boolean overflow, boolean simulate, Operation<Boolean> original){
        var action=simulate?null:IndustrialAudit.begin(this,"experience-fuel",true); boolean success=false;
        try{var result=original.call(fuel, overflow, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "applyCreativeFuel()V", remap = false)
    private void avilixlogger$creative(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"creative-fuel",false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
