package com.roften.avilixlogger.compat.scguns.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import com.roften.avilixlogger.compat.scguns.ReloadStateAudit;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="top.ribs.scguns.network.message.C2SMessageGunLoaded",remap=false)
public abstract class GunLoadedStateMixin {
    @WrapOperation(method="lambda$handle$7",at=@At(value="INVOKE",target="Lcom/mrcrayfish/framework/api/sync/SyncedDataKey;setValue(Lnet/minecraft/world/entity/Entity;Ljava/lang/Object;)V"),remap=false)
    private static void avilixlogger$state(SyncedDataKey key,Entity entity,Object value,Operation<Void> original){
        var before=ReloadStateAudit.before(key,entity);original.call(key,entity,value);ReloadStateAudit.after(key,entity,before,value);
    }
}
