package com.roften.avilixlogger.compat.create.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CartAuditContext;
import com.roften.avilixlogger.compat.create.CartRollbackCoordinator;
import com.roften.avilixlogger.compat.create.CartLootTransferAudit;
import com.roften.avilixlogger.compat.create.CartAutomationAudit;
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
    @WrapMethod(method="tryToMerge")
    private void avilixlogger$merge(ItemEntity other,Operation<Void> original){
        var first=(ItemEntity)(Object)this;
        if(CartAuditContext.rollbackActive&&!CartAuditContext.restoring()&&(CartAuditContext.LOCKED_ENTITIES.contains(first.getUUID())||CartAuditContext.LOCKED_ENTITIES.contains(other.getUUID())))return;
        boolean watched=CartAutomationAudit.worldCovered(first)||CartAutomationAudit.worldCovered(other);
        ItemStack firstBefore=watched?first.getItem().copy():null,secondBefore=watched?other.getItem().copy():null;
        var capture=CartLootTransferAudit.merging(first,other);boolean success=false;
        try{original.call(other);success=true;}finally{
            if(watched&&(!success||first.isRemoved()||other.isRemoved()
                    ||!ItemStack.matches(firstBefore,first.getItem())||!ItemStack.matches(secondBefore,other.getItem())))
                CartAutomationAudit.worldMergeChanged(first,other);
            if(capture!=null)capture.finish(success);
        }
    }
    @WrapMethod(method="playerTouch")
    private void avilixlogger$pickup(Player player,Operation<Void> original){
        var drop=(ItemEntity)(Object)this;
        if(com.roften.avilixlogger.core.CartRestoreLocks.playerLocked(player) || CartAuditContext.rollbackActive && CartAuditContext.LOCKED_ENTITIES.contains(drop.getUUID()))return;
        var scope=CartLootTransferAudit.begin(drop,player);boolean success=false;
        var mixed=scope==null?CartLootTransferAudit.beginMixed(drop,player):null;
        try{original.call(player);success=true;}finally{if(scope!=null)scope.finish(success);if(mixed!=null)mixed.finish(success);}
    }
    @WrapOperation(method="playerTouch",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),require=1)
    private boolean avilixlogger$inventoryAdd(net.minecraft.world.entity.player.Inventory inventory,ItemStack stack,Operation<Boolean> original){
        CartLootTransferAudit.inventoryAdd((ItemEntity)(Object)this);return original.call(inventory,stack);
    }
    @WrapMethod(method="hurt")
    private boolean avilixlogger$hurt(DamageSource source,float amount,Operation<Boolean> original){if(CartAuditContext.rollbackActive && CartAuditContext.LOCKED_ENTITIES.contains(((ItemEntity)(Object)this).getUUID()))return false;return original.call(source,amount);}
    @Inject(method="setItem",at=@At("HEAD"),require=1)
    private void avilixlogger$changed(ItemStack stack,CallbackInfo ci){if(CartAuditContext.rollbackActive)CartRollbackCoordinator.invalidated((ItemEntity)(Object)this);}
}
