package com.roften.avilixlogger.compat.ftbteams.mixin;

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
import com.roften.avilixlogger.compat.ftbteams.FtbTeamState;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.AbstractTeam", remap = false)
public abstract class TeamMixin implements FtbTeamState {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void avilixlogger$bind(CallbackInfo ci) { FtbTeamsAudit.bindProperties(this); }
    @Unique private CompoundTag avilixlogger$lastState;
    public CompoundTag avilixlogger$baseline() { return avilixlogger$lastState; }
    public void avilixlogger$baseline(CompoundTag tag) { avilixlogger$lastState = tag; }
    @WrapMethod(method = "markDirty()V", remap = false)
    private void avilixlogger$dirty(Operation<Void> original) {
        try { original.call(); } finally { FtbTeamsAudit.dirty(this); }
    }
    @WrapMethod(method = "onCreated(Lnet/minecraft/server/level/ServerPlayer;Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$created(ServerPlayer player, UUID id, Operation<Void> original) {
        boolean success = false;
        try { original.call(player, id); success = true; } finally { FtbTeamsAudit.created(this, !success); }
    }
    @WrapMethod(method = "settings(Lnet/minecraft/commands/CommandSourceStack;Ldev/ftb/mods/ftbteams/api/property/TeamProperty;Ljava/lang/String;)I", remap = false)
    private int avilixlogger$settings(CommandSourceStack source, @Coerce Object key, String value, Operation<Integer> original) {
        var action = FtbTeamsAudit.team(this, "settings"); boolean success = false;
        try { var result = original.call(source, key, value); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "updatePropertiesFrom(Ldev/ftb/mods/ftbteams/api/property/TeamPropertyCollection;)V", remap = false)
    private void avilixlogger$properties(@Coerce Object properties, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "properties"); boolean success = false;
        try { original.call(properties); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "copyExtraData(Ldev/ftb/mods/ftbteams/api/Team;)V", remap = false)
    private void avilixlogger$copyExtra(@Coerce Object team, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "copyExtra"); boolean success = false;
        try { original.call(team); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "declineInvitation(Lnet/minecraft/commands/CommandSourceStack;)I", remap = false)
    private int avilixlogger$decline(CommandSourceStack source, Operation<Integer> original) {
        var action = FtbTeamsAudit.team(this, "decline"); boolean success = false;
        try { var result = original.call(source); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "live-nbt-read");
        var history = FtbTeamsAudit.historyChange(this); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { try { if (history != null) history.finish(!success); } finally { try { if (action != null) action.finish(!success); } finally { FtbTeamsAudit.loaded(this); } } }
    }
    @WrapMethod(method = "sendMessage(Ljava/util/UUID;Lnet/minecraft/network/chat/Component;)V", remap = false)
    private void avilixlogger$chat(UUID from, Component text, Operation<Void> original) {
        var action = FtbTeamsAudit.chat(this, from, text); boolean success = false;
        try { original.call(from, text); success = true; } finally { if (action != null) action.finish(!success); }
    }
}
