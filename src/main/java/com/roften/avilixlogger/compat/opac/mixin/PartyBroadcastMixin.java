package com.roften.avilixlogger.compat.opac.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import java.util.function.Predicate;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
@Pseudo
@Mixin(targets = "xaero.pac.common.server.parties.command.PartyOnCommandUpdater", remap = false)
public abstract class PartyBroadcastMixin {
    @WrapMethod(method = "update(Lnet/minecraft/server/level/ServerPlayer;Lxaero/pac/common/server/IServerData;Lxaero/pac/common/server/parties/party/IServerParty;Lxaero/pac/common/server/player/config/IPlayerConfigManager;Ljava/util/function/Predicate;Lnet/minecraft/network/chat/Component;)V", remap = false)
    private void avilixlogger$broadcast(ServerPlayer caller, @Coerce Object data, @Coerce Object party, @Coerce Object configs, Predicate<?> filter, Component message, Operation<Void> original) {
        boolean success = false;
        try { original.call(caller, data, party, configs, filter, message); success = true; }
        finally { OpenPacStateAudit.broadcast(caller, party, message, !success); }
    }
}
