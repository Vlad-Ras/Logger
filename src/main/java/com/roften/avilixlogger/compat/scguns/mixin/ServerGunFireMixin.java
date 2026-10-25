package com.roften.avilixlogger.compat.scguns.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.core.WeaponCombatAudit;
import com.roften.avilixlogger.compat.scguns.ReloadStateAudit;
import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets="top.ribs.scguns.common.network.ServerPlayHandler",remap=false)
public abstract class ServerGunFireMixin {
    @WrapOperation(method="handleShoot",at=@At(value="INVOKE",target="Lcom/mrcrayfish/framework/api/sync/SyncedDataKey;setValue(Lnet/minecraft/world/entity/Entity;Ljava/lang/Object;)V"),remap=false)
    private static void avilixlogger$stopReload(SyncedDataKey key,Entity entity,Object value,Operation<Void> original){
        var before=ReloadStateAudit.before(key,entity);original.call(key,entity,value);ReloadStateAudit.after(key,entity,before,value);
    }
    // Scguns applies wrapped/clamped packet yaw/pitch before creating projectiles or beams.
    @Inject(method="handleShoot",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;setXRot(F)V",shift=At.Shift.AFTER),remap=false)
    private static void avilixlogger$aimed(CallbackInfo ci){WeaponCombatAudit.aimedFire();}
    @WrapMethod(method="handleShoot(Ltop/ribs/scguns/network/message/C2SMessageShoot;Lnet/minecraft/server/level/ServerPlayer;)V",remap=false)
    private static void avilixlogger$fire(@Coerce Object message,ServerPlayer player,Operation<Void> original) {
        var scope=WeaponCombatAudit.beginFire(player);
        try {original.call(message,player);} finally {if(scope!=null)scope.close();}
    }
    @WrapOperation(method="handleShoot",at=@At(value="INVOKE",target="Lnet/neoforged/bus/api/IEventBus;post(Lnet/neoforged/bus/api/Event;)Lnet/neoforged/bus/api/Event;"),remap=false)
    private static Event avilixlogger$accepted(IEventBus bus,Event event,Operation<Event> original) {
        var result=original.call(bus,event);
        if(result.getClass().getName().equals("top.ribs.scguns.event.GunFireEvent$Post"))WeaponCombatAudit.acceptedFire();
        return result;
    }
    @WrapMethod(method="handleUnload(Lnet/minecraft/server/level/ServerPlayer;)V",remap=false)
    private static void avilixlogger$unload(ServerPlayer player,Operation<Void> original) {
        var scope=WeaponCombatAudit.beginReload(player,player.getMainHandItem(),"unload");
        try {original.call(player);}finally {if(scope!=null)scope.close();}
    }
    @WrapMethod(method="handleExtraAmmo(Lnet/minecraft/server/level/ServerPlayer;)V",remap=false)
    private static void avilixlogger$ammo(ServerPlayer player,Operation<Void> original) {
        var scope=WeaponCombatAudit.beginReload(player,player.getMainHandItem(),"ammo_operation");
        try {original.call(player);}finally {if(scope!=null)scope.close();}
    }
}
