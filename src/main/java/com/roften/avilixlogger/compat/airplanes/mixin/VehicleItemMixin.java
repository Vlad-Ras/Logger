package com.roften.avilixlogger.compat.airplanes.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(targets = "immersive_aircraft.item.VehicleItem")
public class VehicleItemMixin {
    @WrapMethod(method = "use", require = 0)
    private InteractionResultHolder<ItemStack> avilixlogger$place(Level level, Player user, InteractionHand hand,
                                                               Operation<InteractionResultHolder<ItemStack>> original) {
        if (level.isClientSide || user == null) return original.call(level, user, hand);
        try (var scope = CauseContext.push(user, CauseContext.Kind.USE_ITEM, user.blockPosition(), user.getItemInHand(hand))) {
            return original.call(level, user, hand);
        }
    }
}
