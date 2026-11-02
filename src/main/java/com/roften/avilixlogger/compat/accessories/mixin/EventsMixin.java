package com.roften.avilixlogger.compat.accessories.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import java.util.Collection;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.Entity;
import com.roften.avilixlogger.core.CauseContext;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.impl.AccessoriesEventHandler", remap = false)
public abstract class EventsMixin {
    @WrapMethod(method = "revalidatePlayer(Lnet/minecraft/server/level/ServerPlayer;)V", remap = false)
    private static void avilixlogger$revalidate(ServerPlayer player, Operation<Void> original) {
        var action = AccessoriesAudit.entity(player, "reload-revalidate"); boolean success = false;
        try { original.call(player); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "onDeath(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Ljava/util/Collection;", remap = false)
    private static Collection<ItemStack> avilixlogger$death(LivingEntity entity, DamageSource source, Operation<Collection<ItemStack>> original) {
        var action = AccessoriesAudit.entity(entity, "death"); boolean success = false;
        try {
            var result = original.call(entity, source); success = true;
            if (action != null) action.deathResult(result, source);
            return result;
        } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "attemptEquipFromUse(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResultHolder;", remap = false)
    private static InteractionResultHolder<ItemStack> avilixlogger$use(Player player, InteractionHand hand, Operation<InteractionResultHolder<ItemStack>> original) {
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.server.isSameThread()) return original.call(player, hand);
        try (var cause = CauseContext.push(player, CauseContext.Kind.USE_ITEM, player.blockPosition(), player.getItemInHand(hand))) {
            var action = AccessoriesAudit.entity(player, "equip-from-use"); boolean success = false;
            try { var result = original.call(player, hand); success = true; return result; }
            finally { if (action != null) action.finish(!success); }
        }
    }
    @WrapMethod(method = "attemptEquipOnEntity(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/entity/Entity;)Lnet/minecraft/world/InteractionResult;", remap = false)
    private static InteractionResult avilixlogger$equipEntity(Player player, InteractionHand hand, Entity target, Operation<InteractionResult> original) {
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.server.isSameThread()) return original.call(player, hand, target);
        try (var cause = CauseContext.push(player, CauseContext.Kind.INTERACT_ENTITY, target.blockPosition(), player.getItemInHand(hand))) {
            var action = target instanceof LivingEntity living ? AccessoriesAudit.entity(living, "equip-on-entity") : null;
            var inventory = AccessoriesAudit.hand(player, "equip-on-entity-player-inventory"); boolean success = false;
            try { var result = original.call(player, hand, target); success = true; return result; }
            finally { try { if (action != null) action.finish(!success); } finally { if (inventory != null) inventory.finish(!success); } }
        }
    }
}
