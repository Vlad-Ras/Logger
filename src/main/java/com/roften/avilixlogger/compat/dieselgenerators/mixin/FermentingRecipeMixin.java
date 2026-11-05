package com.roften.avilixlogger.compat.dieselgenerators.mixin;

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
@Mixin(targets = "com.jesz.createdieselgenerators.content.bulk_fermenter.BulkFermentingRecipe", remap = false)
public abstract class FermentingRecipeMixin {
    @WrapMethod(method = "apply(Lcom/jesz/createdieselgenerators/content/bulk_fermenter/BulkFermenterBlockEntity;Z)Z", remap = false)
    private boolean avilixlogger$apply(@Coerce Object be, boolean simulate, Operation<Boolean> original){
        if (!CaptureHandlers.DIESELGENERATORS_FERMENTINGRECIPEMIXIN_APPLY.enabled()) { return original.call(be, simulate); }
        var action=simulate?null:IndustrialAudit.begin(be,"bulk-fermenting-recipe",true); boolean success=false;
        try{var result=original.call(be, simulate);success=true;return result;}
        finally{if(action!=null)action.finish(!success);}
    }
}
