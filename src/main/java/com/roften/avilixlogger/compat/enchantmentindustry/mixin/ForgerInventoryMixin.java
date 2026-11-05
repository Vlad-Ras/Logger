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
@Mixin(targets = "plus.dragons.createenchantmentindustry.common.processing.forger.BlazeForgerInventory", remap = false)
public abstract class ForgerInventoryMixin {
    @WrapMethod(method = "applyResult()V", remap = false)
    private void avilixlogger$applyResult(Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERINVENTORYMIXIN_APPLYRESULT.enabled()) { original.call(); return; }
        var action=IndustrialAudit.item(this,false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "clearInput()V", remap = false)
    private void avilixlogger$clearInput(Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERINVENTORYMIXIN_CLEARINPUT.enabled()) { original.call(); return; }
        var action=IndustrialAudit.item(this,false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "clear()V", remap = false)
    private void avilixlogger$clear(Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERINVENTORYMIXIN_CLEAR.enabled()) { original.call(); return; }
        var action=IndustrialAudit.item(this,false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method="updateResult()V",remap=false)
    private void avilixlogger$preview(Operation<Void> original){
        if (!CaptureHandlers.ENCHANTMENTINDUSTRY_FORGERINVENTORYMIXIN_PREVIEW.enabled()) { original.call(); return; }
        var action=IndustrialAudit.item(this,false);boolean success=false;
        try{original.call();success=true;}finally{if(action!=null)action.finish(!success);}
    }
}
