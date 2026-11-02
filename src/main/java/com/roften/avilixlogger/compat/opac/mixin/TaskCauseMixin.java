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
    public void avilixlogger$bindCause(CauseContext.Cause cause) { if (!avilixlogger$bound) { avilixlogger$bound = true; avilixlogger$actor = cause; } }
    public CauseContext.Cause avilixlogger$cause() { return avilixlogger$actor; }
}
