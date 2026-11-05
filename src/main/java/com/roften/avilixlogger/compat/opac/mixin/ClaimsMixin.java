package com.roften.avilixlogger.compat.opac.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

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
@Mixin(targets = "xaero.pac.common.server.claims.ServerClaimsManager", remap = false)
public abstract class ClaimsMixin implements com.roften.avilixlogger.compat.opac.OpenPacMutationHooks {
    @Coerce
    @WrapMethod(method = "claim(Lnet/minecraft/resources/ResourceLocation;Ljava/util/UUID;IIIZ)Lxaero/pac/common/claims/player/PlayerChunkClaim;", remap = false)
    private Object avilixlogger$claim(ResourceLocation dim, UUID owner, int sub, int x, int z, boolean force, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_CLAIMSMIXIN_CLAIM.enabled()) { return original.call(dim, owner, sub, x, z, force); }
        var action = OpenPacStateAudit.claim(this, dim, x, z, "claim-state"); boolean success = false;
        try { var result = original.call(dim, owner, sub, x, z, force); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "unclaim(Lnet/minecraft/resources/ResourceLocation;II)V", remap = false)
    private void avilixlogger$unclaim(ResourceLocation dim, int x, int z, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_CLAIMSMIXIN_UNCLAIM.enabled()) { original.call(dim, x, z); return; }
        var action = OpenPacStateAudit.claim(this, dim, x, z, "unclaim"); boolean success = false;
        try { original.call(dim, x, z); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
