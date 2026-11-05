package com.roften.avilixlogger.compat.accessories.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.accessories.AccessoriesAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.level.ServerPlayer;

@Pseudo
@Mixin(targets = "io.wispforest.accessories.networking.holder.SyncHolderChange", remap = false)
public abstract class HolderPacketMixin {
    @WrapMethod(method = "handlePacket(Lio/wispforest/accessories/networking/holder/SyncHolderChange;Lnet/minecraft/world/entity/player/Player;)V", remap = false)
    private static void avilixlogger$handle(@Coerce Object packet, Player player, Operation<Void> original) {
        if (!CaptureHandlers.ACCESSORIES_HOLDERPACKETMIXIN_HANDLE.enabled()) { original.call(packet, player); return; }
        if (!(player instanceof ServerPlayer serverPlayer) || !serverPlayer.server.isSameThread()) { original.call(packet, player); return; }
        try (var cause = CauseContext.push(player, CauseContext.Kind.OTHER, player.blockPosition(), ItemStack.EMPTY)) {
            var action = AccessoriesAudit.entity(player, "packet-SyncHolderChange"); boolean success = false;
            try { original.call(packet, player); success = true; }
            finally { if (action != null) action.finish(!success); }
        }
    }
}
