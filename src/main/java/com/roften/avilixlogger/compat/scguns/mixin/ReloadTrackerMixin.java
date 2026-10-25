package com.roften.avilixlogger.compat.scguns.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import com.roften.avilixlogger.compat.scguns.ReloadStateAudit;
import com.roften.avilixlogger.core.WeaponCombatAudit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;

@Pseudo
@Mixin(targets="top.ribs.scguns.common.ReloadTracker",remap=false)
public abstract class ReloadTrackerMixin {
    @Shadow @Final private ItemStack stack;
    @WrapMethod(method={"increaseAmmo(Lnet/minecraft/world/entity/player/Player;)V","increaseMagAmmo(Lnet/minecraft/world/entity/player/Player;)V","reloadItem(Lnet/minecraft/world/entity/player/Player;)V"},remap=false)
    private void avilixlogger$transfer(Player player,Operation<Void> original) {
        var scope=WeaponCombatAudit.beginReload(player,stack,"ammo_transfer");
        try {original.call(player);}finally {if(scope!=null)scope.close();}
    }
    @WrapMethod(method="loaded(Lnet/minecraft/world/entity/player/Player;)V",remap=false)
    private static void avilixlogger$loaded(Player player,Operation<Void> original) {
        var scope=WeaponCombatAudit.beginReload(player,player.getMainHandItem(),"ammo_transfer");
        try {original.call(player);}finally {if(scope!=null)scope.close();}
    }
    @WrapOperation(method={"onPlayerTick","loaded"},at=@At(value="INVOKE",target="Lcom/mrcrayfish/framework/api/sync/SyncedDataKey;setValue(Lnet/minecraft/world/entity/Entity;Ljava/lang/Object;)V"),remap=false)
    private static void avilixlogger$state(SyncedDataKey key,Entity entity,Object value,Operation<Void> original) {
        var before=ReloadStateAudit.before(key,entity);original.call(key,entity,value);ReloadStateAudit.after(key,entity,before);
    }
}
