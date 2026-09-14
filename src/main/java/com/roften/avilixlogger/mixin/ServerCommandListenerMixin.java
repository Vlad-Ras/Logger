package com.roften.avilixlogger.mixin;

import com.roften.avilixlogger.core.ChatAuditLogger;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerCommandListenerMixin {
    @Shadow public ServerPlayer player;

    @Unique
    private Deque<CauseContext.Scope> avilixlogger$commandScopes;

    @Inject(method = "performUnsignedChatCommand", at = @At("HEAD"), require = 0)
    private void avilixlogger$beforeUnsignedExecution(String command, CallbackInfo ci) {
        ChatAuditLogger.command(player, command);
        avilixlogger$pushCommandCause();
    }

    @Inject(method = "performUnsignedChatCommand", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterUnsignedExecution(String command, CallbackInfo ci) {
        avilixlogger$popCommandCause();
    }

    @Inject(method = "performSignedChatCommand", at = @At("HEAD"), require = 0)
    private void avilixlogger$beforeSignedExecution(
            net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,
            net.minecraft.network.chat.LastSeenMessages lastSeenMessages, CallbackInfo ci) {
        ChatAuditLogger.command(player, packet.command());
        avilixlogger$pushCommandCause();
    }

    @Inject(method = "performSignedChatCommand", at = @At("RETURN"), require = 0)
    private void avilixlogger$afterSignedExecution(
            net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket packet,
            net.minecraft.network.chat.LastSeenMessages lastSeenMessages, CallbackInfo ci) {
        avilixlogger$popCommandCause();
    }

    @Unique
    private void avilixlogger$pushCommandCause() {
        if (player == null) return;
        CauseContext.Scope scope = CauseContext.push(player, CauseContext.Kind.COMMAND,
                player.blockPosition(), player.getMainHandItem());
        if (scope == null) return;

        // Target constructors do not reliably execute mixin instance field initializers.
        // Lazily create the deque before the first command on this connection.
        if (avilixlogger$commandScopes == null) {
            avilixlogger$commandScopes = new ArrayDeque<>();
        }
        avilixlogger$commandScopes.addLast(scope);
    }

    @Unique
    private void avilixlogger$popCommandCause() {
        if (avilixlogger$commandScopes == null) return;
        CauseContext.Scope scope = avilixlogger$commandScopes.pollLast();
        if (scope != null) scope.close();
    }
}
