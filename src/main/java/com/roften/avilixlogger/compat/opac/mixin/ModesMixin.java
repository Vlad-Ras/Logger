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
@Mixin(targets = "xaero.pac.common.server.player.data.ServerPlayerData", remap = false)
public abstract class ModesMixin {
    @WrapMethod(method = "setClaimsModeratorMode(Z)V", remap = false)
    private void avilixlogger$setClaimsModeratorMode(boolean enabled, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "setClaimsModeratorMode"); boolean success = false;
        try { original.call(enabled); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setClaimsAdminMode(Z)V", remap = false)
    private void avilixlogger$setClaimsAdminMode(boolean enabled, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "setClaimsAdminMode"); boolean success = false;
        try { original.call(enabled); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setClaimsNonallyMode(Z)V", remap = false)
    private void avilixlogger$setClaimsNonallyMode(boolean enabled, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "setClaimsNonallyMode"); boolean success = false;
        try { original.call(enabled); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setPartiesAdminMode(Z)V", remap = false)
    private void avilixlogger$setPartiesAdminMode(boolean enabled, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "setPartiesAdminMode"); boolean success = false;
        try { original.call(enabled); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setClaimingMode(Lxaero/pac/common/claims/player/mode/ClaimingMode;)V", remap = false)
    private void avilixlogger$mode(@Coerce Object mode, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "claiming-mode"); boolean success = false;
        try { original.call(mode); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setPartiesImpersonatedPlayerProfile(Lcom/mojang/authlib/GameProfile;)V", remap = false)
    private void avilixlogger$impersonate(@Coerce Object profile, Operation<Void> original) {
        var action = OpenPacStateAudit.modes(this, "party-impersonation"); boolean success = false;
        try { original.call(profile); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
