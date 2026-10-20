package com.roften.avilixlogger.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.core.ChatAuditLogger;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.chat.LastSeenMessages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCommandListenerMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "performUnsignedChatCommand")
    private void avilixlogger$unsigned(String command, Operation<Void> original) {
        ChatAuditLogger.command(player, command);
        try (var scope = CauseContext.push(player, CauseContext.Kind.COMMAND,
                player.blockPosition(), player.getMainHandItem())) {
            original.call(command);
        }
    }
    @WrapMethod(method = "performSignedChatCommand")
    private void avilixlogger$signed(ServerboundChatCommandSignedPacket packet, LastSeenMessages messages,
                                     Operation<Void> original) {
        ChatAuditLogger.command(player, packet.command());
        try (var scope = CauseContext.push(player, CauseContext.Kind.COMMAND,
                player.blockPosition(), player.getMainHandItem())) {
            original.call(packet, messages);
        }
    }
    @WrapMethod(method = "handleInteract")
    private void avilixlogger$entityPacket(net.minecraft.network.protocol.game.ServerboundInteractPacket packet,
                                           Operation<Void> original) {
        if (!player.server.isSameThread()) { original.call(packet); return; }
        var target = packet.getTarget(player.serverLevel());
        if (target == null) { original.call(packet); return; }
        try (var scope = CauseContext.push(player, CauseContext.Kind.INTERACT_ENTITY,
                target.blockPosition(), player.getMainHandItem())) {
            original.call(packet);
        }
    }

}
