package com.roften.avilixlogger.compat.airplanes.mixin;

import net.minecraft.core.component.DataComponents;
import com.roften.avilixlogger.compat.airplanes.AirplanesCompatHooks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
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
            CompoundTag tag = new CompoundTag();
            if (stack.has(DataComponents.CUSTOM_DATA) && stack.get(DataComponents.CUSTOM_DATA) != null) {
                tag = stack.get(DataComponents.CUSTOM_DATA).copyTag();
            }

            // Don't overwrite if already present (e.g. stack cloned).
            // Don't overwrite admin/manual owner tags:
            // - if admin has set owner_name (with/without UUID), keep it intact
            // - otherwise default to the player who placed the plane
            boolean hasName = tag.contains("owner_name") && !tag.getString("owner_name").isBlank();
            boolean hasUuid = tag.contains("owner_uuid") && !tag.getString("owner_uuid").isBlank();
            boolean hasOwner = tag.contains("owner") && !tag.getString("owner").isBlank();

            if (!hasName && !hasUuid && !hasOwner) {
                tag.putString("owner", user.getUUID().toString());
                tag.putString("owner_uuid", user.getUUID().toString());
                tag.putString("owner_name", user.getName().getString());
            } else if (!hasName) {
                // We have UUID but no name hint: fill name for better logs.
                tag.putString("owner_name", user.getName().getString());
            }

            stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
            try { if (world instanceof ServerLevel sl) AirplanesCompatHooks.notePlaneItemUse(sl, user); } catch (Throwable ignored2) {}
        } catch (Throwable ignored) {
        }
    }

    /**
     * Explicit action log ("поставил самолет") for parity with the standalone AirPlanesLogger mod.
     * We still keep ENTITY_SPAWN logs as well.
     */
    @Inject(
            method = "use",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z", shift = At.Shift.AFTER),
            require = 0
    )
    private void avilixlogger$logPlacement(Level world, Player user, InteractionHand hand, CallbackInfoReturnable<InteractionResultHolder<ItemStack>> cir) {
        try {
            if (world == null || world.isClientSide) return;
            if (!(world instanceof ServerLevel sl)) return;
            if (user == null) return;
            ItemStack stack = user.getItemInHand(hand);
            if (stack == null || stack.isEmpty()) return;
            AirplanesCompatHooks.logPlacement(sl, user, stack);
        } catch (Throwable ignored) {}
    }
}