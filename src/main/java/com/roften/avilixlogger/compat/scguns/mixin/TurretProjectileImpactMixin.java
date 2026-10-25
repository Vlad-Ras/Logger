package com.roften.avilixlogger.compat.scguns.mixin;

import com.roften.avilixlogger.core.WeaponCombatAudit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observe the native accepted hit; generic projectile logging skips traced turret arrows. */
@Pseudo
@Mixin(targets="top.ribs.scguns.entity.projectile.turret.TurretProjectileEntity",remap=false)
public abstract class TurretProjectileImpactMixin {
    @Inject(method="onHitBlock",at=@At("TAIL"),remap=false)
    private void avilixlogger$block(BlockHitResult hit,CallbackInfo ci){WeaponCombatAudit.blockImpact((Entity)(Object)this,hit);}
}
