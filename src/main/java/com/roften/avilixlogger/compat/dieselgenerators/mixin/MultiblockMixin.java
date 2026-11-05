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
@Mixin(targets = {"com.jesz.createdieselgenerators.content.oil_barrel.OilBarrelBlockEntity","com.jesz.createdieselgenerators.content.bulk_fermenter.BulkFermenterBlockEntity","com.jesz.createdieselgenerators.content.distillation.DistillationTankBlockEntity","com.jesz.createdieselgenerators.content.diesel_engine.modular.ModularDieselEngineBlockEntity"}, remap = false)
public abstract class MultiblockMixin {
    @WrapMethod(method = "setController(Lnet/minecraft/core/BlockPos;)V", remap = false)
    private void avilixlogger$controller(BlockPos pos, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_MULTIBLOCKMIXIN_CONTROLLER.enabled()) { original.call(pos); return; }
        var action=IndustrialAudit.begin(this,"set-controller",false); boolean success=false;
        try{original.call(pos);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "removeController(Z)V", remap = false)
    private void avilixlogger$split(boolean keep, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_MULTIBLOCKMIXIN_SPLIT.enabled()) { original.call(keep); return; }
        var action=IndustrialAudit.begin(this,"remove-controller",false); boolean success=false;
        try{original.call(keep);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "setWidth(I)V", remap = false)
    private void avilixlogger$width(int size, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_MULTIBLOCKMIXIN_WIDTH.enabled()) { original.call(size); return; }
        var action=IndustrialAudit.begin(this,"width",false); boolean success=false;
        try{original.call(size);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "setHeight(I)V", remap = false)
    private void avilixlogger$height(int size, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_MULTIBLOCKMIXIN_HEIGHT.enabled()) { original.call(size); return; }
        var action=IndustrialAudit.begin(this,"height",false); boolean success=false;
        try{original.call(size);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
