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
@Mixin(targets = "xaero.pac.common.server.parties.party.ServerParty", remap = false)
public abstract class PartyMixin {
    @WrapMethod(method = "changeOwner(Ljava/util/UUID;Ljava/lang/String;)Z", remap = false)
    private boolean avilixlogger$owner(UUID id, String name, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_OWNER.enabled()) { return original.call(id, name); }
        var action = OpenPacStateAudit.party(this, "party-owner"); boolean success = false;
        try { var result = original.call(id, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "addMember(Ljava/util/UUID;Lxaero/pac/common/parties/party/member/PartyMemberRank;Ljava/lang/String;)Lxaero/pac/common/parties/party/member/PartyMember;", remap = false)
    private Object avilixlogger$member(UUID id, @Coerce Object rank, String name, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_MEMBER.enabled()) { return original.call(id, rank, name); }
        var action = OpenPacStateAudit.party(this, "party-member"); boolean success = false;
        try { var result = original.call(id, rank, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "addMemberClean(Ljava/util/UUID;Lxaero/pac/common/parties/party/member/PartyMemberRank;Ljava/lang/String;)Lxaero/pac/common/parties/party/member/PartyMember;", remap = false)
    private Object avilixlogger$memberClean(UUID id, @Coerce Object rank, String name, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_MEMBERCLEAN.enabled()) { return original.call(id, rank, name); }
        var action = OpenPacStateAudit.party(this, "party-memberClean"); boolean success = false;
        try { var result = original.call(id, rank, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "removeMember(Ljava/util/UUID;)Lxaero/pac/common/parties/party/member/PartyMember;", remap = false)
    private Object avilixlogger$removeMember(UUID id, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_REMOVEMEMBER.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.party(this, "party-removeMember"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addAllyParty(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$ally(UUID id, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_ALLY.enabled()) { original.call(id); return; }
        var action = OpenPacStateAudit.party(this, "party-ally"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addAllyPartyClean(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$allyClean(UUID id, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_ALLYCLEAN.enabled()) { original.call(id); return; }
        var action = OpenPacStateAudit.party(this, "party-allyClean"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeAllyParty(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$unally(UUID id, Operation<Void> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_UNALLY.enabled()) { original.call(id); return; }
        var action = OpenPacStateAudit.party(this, "party-unally"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "invitePlayer(Ljava/util/UUID;Ljava/lang/String;)Lxaero/pac/common/parties/party/member/PartyInvite;", remap = false)
    private Object avilixlogger$invite(UUID id, String name, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_INVITE.enabled()) { return original.call(id, name); }
        var action = OpenPacStateAudit.party(this, "party-invite"); boolean success = false;
        try { var result = original.call(id, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "invitePlayerClean(Ljava/util/UUID;Ljava/lang/String;)Lxaero/pac/common/parties/party/member/PartyInvite;", remap = false)
    private Object avilixlogger$inviteClean(UUID id, String name, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_INVITECLEAN.enabled()) { return original.call(id, name); }
        var action = OpenPacStateAudit.party(this, "party-inviteClean"); boolean success = false;
        try { var result = original.call(id, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "uninvitePlayer(Ljava/util/UUID;)Lxaero/pac/common/parties/party/member/PartyInvite;", remap = false)
    private Object avilixlogger$uninvite(UUID id, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_UNINVITE.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.party(this, "party-uninvite"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "removeInvitedPlayer(Ljava/util/UUID;)Lxaero/pac/common/parties/party/member/PartyInvite;", remap = false)
    private Object avilixlogger$removeInvite(UUID id, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_REMOVEINVITE.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.party(this, "party-removeInvite"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setRankTyped(Lxaero/pac/common/parties/party/member/PartyMember;Lxaero/pac/common/parties/party/member/PartyMemberRank;)Z", remap = false)
    private boolean avilixlogger$rank(@Coerce Object member, @Coerce Object rank, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_RANK.enabled()) { return original.call(member, rank); }
        var action = OpenPacStateAudit.party(this, "party-rank"); boolean success = false;
        try { var result = original.call(member, rank); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "updateUsername(Lxaero/pac/common/parties/party/member/PartyMember;Ljava/lang/String;)Z", remap = false)
    private boolean avilixlogger$username(@Coerce Object member, String name, Operation<Boolean> original) {
        if (!CaptureHandlers.OPAC_PARTYMIXIN_USERNAME.enabled()) { return original.call(member, name); }
        var action = OpenPacStateAudit.party(this, "party-username"); boolean success = false;
        try { var result = original.call(member, name); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
