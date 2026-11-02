package com.roften.avilixlogger.compat.dieselgenerators.mixin;
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
@Mixin(targets = "com.jesz.createdieselgenerators.content.distillation.DistillationTankBlockEntity", remap = false)
public abstract class DistillationWindowMixin {
    @WrapMethod(method = "toggleWindows()V", remap = false)
    private void avilixlogger$toggle(Operation<Void> original){
        var action=IndustrialAudit.begin(this,"toggle-windows",false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "setWindows(Z)V", remap = false)
    private void avilixlogger$windows(boolean windows, Operation<Void> original){
        var action=IndustrialAudit.begin(this,"set-windows",false); boolean success=false;
        try{original.call(windows);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
