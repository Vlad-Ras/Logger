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
import com.roften.avilixlogger.compat.opac.OpenPacTaskCause;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = {
    "xaero.pac.common.server.claims.player.task.PlayerAreaClaimActionSpreadoutTask",
    "xaero.pac.common.server.claims.player.task.PlayerClaimReplaceSpreadoutTask",
    "xaero.pac.common.server.parties.party.task.PartyRemovalSpreadoutTask",
    "xaero.pac.common.server.expiration.task.ObjectExpirationCheckSpreadoutTask"
}, remap = false)
public abstract class TaskCauseMixin implements OpenPacTaskCause {
    @Unique private boolean avilixlogger$bound;
    @Unique private CauseContext.Cause avilixlogger$actor;
    // Bind at submission/creation, before OPAC's private per-player queues or later ticks.
    // This stores only immutable identity data. Publication remains AuthCore-gated.
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void avilixlogger$created(CallbackInfo callback) {
        if (OpenPacStateAudit.available()) avilixlogger$bindCause(CauseContext.peek());
    }
    public void avilixlogger$bindCause(CauseContext.Cause cause) { if (!avilixlogger$bound) { avilixlogger$bound = true; avilixlogger$actor = cause; } }
    public CauseContext.Cause avilixlogger$cause() { return avilixlogger$actor; }
}
