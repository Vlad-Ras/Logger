package com.roften.avilixlogger.compat.scguns.mixin;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.projectile.Projectile;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Scguns can fire vanilla arrows too. Other projectiles retain a null field only. */
@Mixin(value=Projectile.class,remap=false)
public abstract class VanillaProjectileTraceMixin implements WeaponTraceAccess {
    @Unique private WeaponCombatTrace.Shot avilixlogger$trace;
    public WeaponCombatTrace.Shot avilixlogger$shot(){return avilixlogger$trace;}
    public void avilixlogger$shot(WeaponCombatTrace.Shot shot){avilixlogger$trace=shot;}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"),remap=false)
    private void avilixlogger$save(CompoundTag tag,CallbackInfo ci){if(avilixlogger$trace!=null)tag.put(WeaponCombatTrace.SAVE_KEY,avilixlogger$trace.tag());}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"),remap=false)
    private void avilixlogger$load(CompoundTag tag,CallbackInfo ci){avilixlogger$trace=WeaponCombatTrace.Shot.read(tag.getCompound(WeaponCombatTrace.SAVE_KEY));}
}
