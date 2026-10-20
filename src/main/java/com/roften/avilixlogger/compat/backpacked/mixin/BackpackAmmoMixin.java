package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(ProjectileWeaponItem.class)
public abstract class BackpackAmmoMixin {
    @WrapMethod(method="useAmmo")
    private static ItemStack avilixlogger$ammo(ItemStack weapon,ItemStack ammo,LivingEntity entity,boolean multishot,Operation<ItemStack> original){
        if(entity instanceof Player p)try(var scope=BackpackAudit.scope(p,"augment:quiverlink")){return original.call(weapon,ammo,entity,multishot);}
        return original.call(weapon,ammo,entity,multishot);
    }
}
