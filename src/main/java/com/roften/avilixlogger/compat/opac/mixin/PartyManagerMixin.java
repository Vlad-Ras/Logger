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
@Mixin(targets = "xaero.pac.common.server.parties.party.PartyManager", remap = false)
public abstract class PartyManagerMixin {
    @WrapMethod(method = "addParty(Lxaero/pac/common/server/parties/party/ServerParty;)V", remap = false)
    private void avilixlogger$addParty(@Coerce Object party, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_PARTYMANAGERMIXIN_ADDPARTY.enabled()) { original.call(party); return; }
        var action = OpenPacStateAudit.lifecycle(this, party, "party-addParty"); boolean success = false;
        try { original.call(party); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeTypedParty(Lxaero/pac/common/server/parties/party/ServerParty;)V", remap = false)
    private void avilixlogger$removeTypedParty(@Coerce Object party, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_PARTYMANAGERMIXIN_REMOVETYPEDPARTY.enabled()) { original.call(party); return; }
        var action = OpenPacStateAudit.lifecycle(this, party, "party-removeTypedParty"); boolean success = false;
        try { original.call(party); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
