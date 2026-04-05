package com.roften.avilixlogger.compat.airplanes.mixin;

import com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Immersive Aircraft / Man of Many Planes compat:
 * - Writes owner info into the plane item stack's CUSTOM_DATA so the spawned VehicleEntity can persist it.
 */
@Mixin(targets = "immersive_aircraft.item.VehicleItem")
public class VehicleItemMixin {

    @Inject(method = "use", at = @At("HEAD"), require = 0)
    private void avilixlogger$tagOwner(Level world, Player user, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        if (world == null || world.isClientSide) return;
        if (user == null) return;

        ItemStack stack = user.getItemInHand(hand);
        if (stack == null || stack.isEmpty()) return;

        try {
            // Placement must remember only the recent placer for attribution/logging.
            // Ownership itself is assigned exclusively via /owner set and persisted separately.
            try { if (world instanceof ServerLevel sl) AirplanesCompatHooks.notePlaneItemUse(sl, user); } catch (Throwable ignored2) {}
        } catch (Throwable ignored) {
        }
    }

    // NOTE:
    // We intentionally do NOT emit a second "plane place" log here.
    // Plane placement is already captured via the server-side entity spawn hook (LoggerEventHandlers)
    // which records entity UUID + NBT and is the canonical source.
}