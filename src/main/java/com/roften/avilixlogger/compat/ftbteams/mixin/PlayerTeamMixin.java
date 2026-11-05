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
@Mixin(targets = "dev.ftb.mods.ftbteams.data.PlayerTeam", remap = false)
public abstract class PlayerTeamMixin {
    @WrapMethod(method = "setEffectiveTeam(Ldev/ftb/mods/ftbteams/data/AbstractTeam;)V", remap = false)
    private void avilixlogger$effectiveTeam(@Coerce Object team, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PLAYERTEAMMIXIN_EFFECTIVETEAM.enabled()) { original.call(team); return; }
        var action = FtbTeamsAudit.team(this, "effectiveTeam"); boolean success = false;
        try { original.call(team); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setPlayerName(Ljava/lang/String;)V", remap = false)
    private void avilixlogger$playerName(String name, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PLAYERTEAMMIXIN_PLAYERNAME.enabled()) { original.call(name); return; }
        var action = FtbTeamsAudit.team(this, "playerName"); boolean success = false;
        try { original.call(name); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "deserializeNBT(Lnet/minecraft/nbt/CompoundTag;Lnet/minecraft/core/HolderLookup$Provider;)V", remap = false)
    private void avilixlogger$readFull(CompoundTag tag, HolderLookup.Provider provider, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PLAYERTEAMMIXIN_READFULL.enabled()) { original.call(tag, provider); return; }
        var action = FtbTeamsAudit.team(this, "live-subtype-nbt-read");
        var history = FtbTeamsAudit.historyChange(this); boolean success = false;
        try { original.call(tag, provider); success = true; }
        finally { try { if (history != null) history.finish(!success); } finally { try { if (action != null) action.finish(!success); } finally { FtbTeamsAudit.loaded(this); } } }
    }
    @WrapMethod(method = "setOnline(Z)V", remap = false)
    private void avilixlogger$online(boolean online, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PLAYERTEAMMIXIN_ONLINE.enabled()) { original.call(online); return; }
        var action = FtbTeamsAudit.team(this, "player-presence"); boolean success = false;
        try { original.call(online); success = true; } finally { if (action != null) action.finish(!success); }
    }
}
