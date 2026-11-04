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
@Pseudo
@Mixin(targets = "xaero.pac.common.claims.player.impersonation.PlayerClaimImpersonationInfo", remap = false)
public abstract class ImpersonationMixin {
    @WrapMethod(method = "setPlayerId(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$owner(UUID id, Operation<Void> original) {
        var action = OpenPacStateAudit.impersonation(this, "claim-impersonation-owner"); boolean success = false;
        try { original.call(id); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setSubIndex(Lxaero/pac/common/claims/player/mode/api/IClaimingModeAPI;I)V", remap = false)
    private void avilixlogger$sub(@Coerce Object mode, int index, Operation<Void> original) {
        var action = OpenPacStateAudit.impersonation(this, "claim-impersonation-sub"); boolean success = false;
        try { original.call(mode, index); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "reset()V", remap = false)
    private void avilixlogger$reset(Operation<Void> original) {
        var action = OpenPacStateAudit.impersonation(this, "claim-impersonation-reset"); boolean success = false;
        try { original.call(); success = true; } finally { if (action != null) action.finish(!success); }
    }
}
