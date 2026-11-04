package com.roften.avilixlogger.compat.opac.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.Coerce;

/** The authenticated request's caller is independent of the claim owner/impersonation mode. */
@Pseudo
@Mixin(targets = "xaero.pac.common.server.claims.player.request.PlayerClaimActionRequestHandler", remap = false)
public abstract class ClaimRequestCauseMixin {
    @WrapMethod(method = "onReceive(Lnet/minecraft/server/level/ServerPlayer;Lxaero/pac/common/claims/action/request/ClaimActionRequest;)V", remap = false)
    private void avilixlogger$request(ServerPlayer caller, @Coerce Object request, Operation<Void> original) {
        if (!OpenPacStateAudit.available() || !caller.server.isSameThread()) { original.call(caller, request); return; }
        try (var scope = CauseContext.push(caller, CauseContext.Kind.OTHER, caller.blockPosition(), ItemStack.EMPTY)) {
            original.call(caller, request);
        }
    }
}
