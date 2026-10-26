package com.roften.avilixlogger.compat.blockpack.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.*;
import com.roften.avilixlogger.compat.blockpack.BlockPackJoinGiftAudit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo @Mixin(targets="com.blockfactory.blockpack.event.PlayerJoinHandler",remap=false)
public abstract class GiftDeliveryMixin {
    @WrapMethod(method="giveOrDrop(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V")
    private static void avilixlogger$gift(ServerPlayer player,ItemStack stack,Operation<Void> original){
        var capture=BlockPackJoinGiftAudit.begin(player,stack);if(capture==null){original.call(player,stack);return;}
        boolean completed=false;try{original.call(player,stack);completed=true;}finally{capture.finish(completed);}
    }
    @WrapOperation(method="giveOrDrop(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V",at=@At(value="INVOKE",target="Lnet/minecraft/world/entity/player/Inventory;add(Lnet/minecraft/world/item/ItemStack;)Z"),require=1)
    private static boolean avilixlogger$add(Inventory inventory,ItemStack stack,Operation<Boolean> original){
        var capture=BlockPackJoinGiftAudit.current();if(capture==null)return original.call(inventory,stack);
        int offered;ItemStack prototype;long before;
        try{offered=stack.getCount();prototype=stack.copyWithCount(1);before=BlockPackJoinGiftAudit.count(inventory,prototype);}
        catch(RuntimeException|LinkageError failure){capture.invalid();return original.call(inventory,stack);}
        boolean result=original.call(inventory,stack);
        try{capture.added(offered,before,BlockPackJoinGiftAudit.count(inventory,prototype));}catch(RuntimeException|LinkageError failure){capture.invalid();}
        return result;
    }
    @WrapOperation(method="giveOrDrop(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/item/ItemStack;)V",at=@At(value="INVOKE",target="Lnet/minecraft/server/level/ServerPlayer;drop(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/entity/item/ItemEntity;"),require=1)
    private static ItemEntity avilixlogger$drop(ServerPlayer player,ItemStack stack,boolean random,Operation<ItemEntity> original){var capture=BlockPackJoinGiftAudit.current();var result=original.call(player,stack,random);if(capture!=null)capture.dropped(result);return result;}
}
