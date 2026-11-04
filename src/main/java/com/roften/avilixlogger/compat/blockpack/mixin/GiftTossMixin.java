package com.roften.avilixlogger.compat.blockpack.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.blockpack.BlockPackJoinGiftAudit;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.CommonHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Observe only the existing player-toss spawn call; inactive outside a licensed BlockPack gift. */
@Mixin(value=CommonHooks.class,remap=false)
public abstract class GiftTossMixin {
    @WrapOperation(method="onPlayerTossEvent(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;",at=@At(value="INVOKE",target="Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"),require=1)
    private static boolean avilixlogger$giftSpawn(Level level,Entity entity,Operation<Boolean> original){
        var capture=BlockPackJoinGiftAudit.current();boolean accepted=original.call(level,entity);
        if(capture!=null)try{capture.spawned(entity,accepted);}catch(RuntimeException|LinkageError failure){capture.invalid();}
        return accepted;
    }
}
