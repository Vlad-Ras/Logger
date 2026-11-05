package com.roften.avilixlogger.compat.ftbteams.mixin;

import com.roften.avilixlogger.core.CaptureHandlers;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.ftbteams.FtbTeamsAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import net.minecraft.network.chat.Component;
import com.mojang.authlib.GameProfile;
import java.util.*;
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.PartyTeam", remap = false)
public abstract class PartyMixin {
    @WrapMethod(method = "join(Lnet/minecraft/server/level/ServerPlayer;Lcom/mojang/authlib/GameProfile;)I", remap = false)
    private int avilixlogger$join(ServerPlayer player, GameProfile profile, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_JOIN.enabled()) { return original.call(player, profile); }
        var action = FtbTeamsAudit.team(this, "join"); boolean success = false;
        try { var result = original.call(player, profile); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "invite(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$invite(ServerPlayer player, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_INVITE.enabled()) { return original.call(player, profiles); }
        var action = FtbTeamsAudit.team(this, "invite"); boolean success = false;
        try { var result = original.call(player, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "kick(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$kick(CommandSourceStack source, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_KICK.enabled()) { return original.call(source, profiles); }
        var action = FtbTeamsAudit.team(this, "kick"); boolean success = false;
        try { var result = original.call(source, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "promote(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$promote(ServerPlayer player, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_PROMOTE.enabled()) { return original.call(player, profiles); }
        var action = FtbTeamsAudit.team(this, "promote"); boolean success = false;
        try { var result = original.call(player, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "demote(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$demote(ServerPlayer player, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_DEMOTE.enabled()) { return original.call(player, profiles); }
        var action = FtbTeamsAudit.team(this, "demote"); boolean success = false;
        try { var result = original.call(player, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "transferOwnership(Lnet/minecraft/commands/CommandSourceStack;Lcom/mojang/authlib/GameProfile;)I", remap = false)
    private int avilixlogger$owner(CommandSourceStack source, GameProfile profile, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_OWNER.enabled()) { return original.call(source, profile); }
        var action = FtbTeamsAudit.team(this, "owner"); boolean success = false;
        try { var result = original.call(source, profile); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "leave(Ljava/util/UUID;)I", remap = false)
    private int avilixlogger$leave(UUID player, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_LEAVE.enabled()) { return original.call(player); }
        var action = FtbTeamsAudit.team(this, "leave"); boolean success = false;
        try { var result = original.call(player); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addAlly(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$ally(CommandSourceStack source, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_ALLY.enabled()) { return original.call(source, profiles); }
        var action = FtbTeamsAudit.team(this, "ally"); boolean success = false;
        try { var result = original.call(source, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeAlly(Lnet/minecraft/commands/CommandSourceStack;Ljava/util/Collection;)I", remap = false)
    private int avilixlogger$unally(CommandSourceStack source, Collection<GameProfile> profiles, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_UNALLY.enabled()) { return original.call(source, profiles); }
        var action = FtbTeamsAudit.team(this, "unally"); boolean success = false;
        try { var result = original.call(source, profiles); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "forceDisband(Lnet/minecraft/commands/CommandSourceStack;)I", remap = false)
    private int avilixlogger$disband(CommandSourceStack source, Operation<Integer> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_DISBAND.enabled()) { return original.call(source); }
        var action = FtbTeamsAudit.team(this, "disband"); boolean success = false;
        try { var result = original.call(source); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "kickPlayerForcibly(Lnet/minecraft/server/level/ServerPlayer;)V", remap = false)
    private void avilixlogger$forcedKick(ServerPlayer player, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_FORCEDKICK.enabled()) { original.call(player); return; }
        var action = FtbTeamsAudit.team(this, "forcedKick"); boolean success = false;
        try { original.call(player); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$readFull(CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PARTYMIXIN_READFULL.enabled()) { original.call(tag, provider); return; }
        var action = FtbTeamsAudit.team(this, "live-subtype-nbt-read");
        var history = FtbTeamsAudit.historyChange(this); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { try { if (history != null) history.finish(!success); } finally { try { if (action != null) action.finish(!success); } finally { FtbTeamsAudit.loaded(this); } } }
    }
}
