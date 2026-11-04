package com.roften.avilixlogger.compat.scguns.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.llamalad7.mixinextras.sugar.Local;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.*;
import net.neoforged.bus.api.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="top.ribs.scguns.entity.projectile.ProjectileEntity",remap=false)
public abstract class ScgunsProjectileTraceMixin implements WeaponTraceAccess {
    @Unique private WeaponCombatTrace.Shot avilixlogger$trace;
    public WeaponCombatTrace.Shot avilixlogger$shot(){return avilixlogger$trace;}
    public void avilixlogger$shot(WeaponCombatTrace.Shot shot){avilixlogger$trace=shot;}
    @Shadow public abstract LivingEntity getShooter();
    @Shadow public abstract ItemStack getWeapon();
    @Inject(method="<init>",at=@At("TAIL"),remap=false)
    private void avilixlogger$created(CallbackInfo ci){WeaponCombatAudit.constructed((Entity)(Object)this,getShooter(),getWeapon());}
    @Inject(method="addAdditionalSaveData",at=@At("TAIL"),remap=false)
    private void avilixlogger$save(CompoundTag tag,CallbackInfo ci){if(avilixlogger$trace!=null)tag.put(WeaponCombatTrace.SAVE_KEY,avilixlogger$trace.tag());}
    @Inject(method="readAdditionalSaveData",at=@At("TAIL"),remap=false)
    private void avilixlogger$load(CompoundTag tag,CallbackInfo ci){avilixlogger$trace=WeaponCombatTrace.Shot.read(tag.getCompound(WeaponCombatTrace.SAVE_KEY));}
    @WrapOperation(method="onHit",at=@At(value="INVOKE",target="Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),remap=false)
    private Event avilixlogger$impact(IEventBus bus,Event event,Operation<Event> original,@Local(argsOnly=true) HitResult hit) {
        var result=original.call(bus,event);
        if(result instanceof ICancellableEvent cancel && !cancel.isCanceled() && hit instanceof BlockHitResult block)
            WeaponCombatAudit.blockImpact((Entity)(Object)this,block);
        return result;
    }
}
