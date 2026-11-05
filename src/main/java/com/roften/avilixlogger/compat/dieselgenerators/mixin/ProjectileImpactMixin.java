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
@Mixin(targets = "com.jesz.createdieselgenerators.content.tools.ChemicalSprayerProjectileEntity", remap = false)
public abstract class ProjectileImpactMixin implements IndustrialChemicalProjectile {
    @WrapMethod(method = "onHitEntity(Lnet/minecraft/world/phys/EntityHitResult;)V", remap = false)
    private void avilixlogger$impact(net.minecraft.world.phys.EntityHitResult hit,Operation<Void> original){
        if (!CaptureHandlers.DIESELGENERATORS_PROJECTILEIMPACTMIXIN_IMPACT.enabled()) { original.call(hit); return; }
        try(var cause=IndustrialImpacts.cause(this)){
            var action=IndustrialImpacts.hit(this,hit.getEntity());boolean success=false;
            try{original.call(hit);success=true;}finally{if(action!=null)action.finish(!success);}
        }
    }
}
