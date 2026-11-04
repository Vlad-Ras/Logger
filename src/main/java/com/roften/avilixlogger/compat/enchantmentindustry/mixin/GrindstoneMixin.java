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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.kinetics.grindstone.GrindstoneDrainBlockEntity", remap = false)
public abstract class GrindstoneMixin {
    @WrapMethod(method = "applyRecipe()V", remap = false)
    private void avilixlogger$recipe(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"grinding-result",true); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "clearContent()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"clear-grindstone",true); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "destroy()V", remap = false)
    private void avilixlogger$destroy(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"destroy-grindstone",true); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
