package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.compat.create.CartRollbackCoordinator;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class CartDropLockMixin {
    @WrapMethod(method="playerTouch")
    private void avilixlogger$pickup(Player player,Operation<Void> original){if(CartAuditContext.rollbackActive && CartAuditContext.LOCKED_ENTITIES.contains(((ItemEntity)(Object)this).getUUID()))return;original.call(player);}
    @WrapMethod(method="hurt")
    private boolean avilixlogger$hurt(DamageSource source,float amount,Operation<Boolean> original){if(CartAuditContext.rollbackActive && CartAuditContext.LOCKED_ENTITIES.contains(((ItemEntity)(Object)this).getUUID()))return false;return original.call(source,amount);}
    @Inject(method="setItem",at=@At("HEAD"),require=1)
    private void avilixlogger$changed(ItemStack stack,CallbackInfo ci){if(CartAuditContext.rollbackActive)CartRollbackCoordinator.invalidated((ItemEntity)(Object)this);}
}
