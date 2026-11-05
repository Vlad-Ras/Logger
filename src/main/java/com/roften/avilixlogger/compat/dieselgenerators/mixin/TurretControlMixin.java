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
@Mixin(targets = "com.jesz.createdieselgenerators.content.turret.TurretBlockEntity", remap = false)
public abstract class TurretControlMixin {
    @WrapMethod(method = "setControllingPlayer(Lnet/minecraft/world/entity/player/Player;)V", remap = false)
    private void avilixlogger$control(Player player, Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_TURRETCONTROLMIXIN_CONTROL.enabled()) { original.call(player); return; }
        var action=IndustrialAudit.begin(this,"turret-controller",false); boolean success=false;
        try{original.call(player);success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
    @WrapMethod(method = "removePlayer()V", remap = false)
    private void avilixlogger$stop(Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_TURRETCONTROLMIXIN_STOP.enabled()) { original.call(); return; }
        var action=IndustrialAudit.begin(this,"turret-stop-controller",false); boolean success=false;
        try{original.call();success=true;}
        finally{if(action!=null)action.finish(!success);}
    }
}
