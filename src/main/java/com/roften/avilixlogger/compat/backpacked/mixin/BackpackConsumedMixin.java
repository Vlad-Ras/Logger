package com.roften.avilixlogger.compat.backpacked.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.backpacked.BackpackAudit;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
@Mixin(LivingEntity.class)
public abstract class BackpackConsumedMixin {
    @WrapMethod(method="checkTotemDeathProtection")
    private boolean avilixlogger$totem(DamageSource damage,Operation<Boolean> original){
        if((Object)this instanceof Player player)try(var scope=BackpackAudit.scope(player,"augment:immortal")){return original.call(damage);}
        return original.call(damage);
    }
}
