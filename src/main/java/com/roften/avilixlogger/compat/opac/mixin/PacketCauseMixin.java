package com.roften.avilixlogger.compat.opac.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerPlayer;
import java.util.*;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.world.item.ItemStack;
@Pseudo
@Mixin(targets = "xaero.pac.common.packet.ServerPacketReceiver", remap = false)
public abstract class PacketCauseMixin {
    @WrapMethod(method = "getTask(Lxaero/pac/common/packet/type/PacketType;Ljava/lang/Object;Lnet/minecraft/server/level/ServerPlayer;)Ljava/lang/Runnable;", remap = false)
    private Runnable avilixlogger$task(@Coerce Object type, Object packet, ServerPlayer player, Operation<Runnable> original) {
        Runnable task = original.call(type, packet, player);
        return () -> {
            if (!OpenPacStateAudit.available()) { task.run(); return; }
            try (var cause = CauseContext.push(player, CauseContext.Kind.OTHER, player.blockPosition(), ItemStack.EMPTY)) { task.run(); }
        };
    }
}
