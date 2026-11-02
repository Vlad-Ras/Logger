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
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.TeamManagerImpl", remap = false)
public abstract class ManagerMixin {
    @WrapMethod(method = "deleteTeam(Ldev/ftb/mods/ftbteams/data/AbstractTeam;)V", remap = false)
    private void avilixlogger$deleteTeam(@Coerce Object team, Operation<Void> original) {
        var action = FtbTeamsAudit.team(team, "delete-team"); boolean success = false;
        try { original.call(team); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setChatRedirected(Lnet/minecraft/server/level/ServerPlayer;Z)V", remap = false)
    private void avilixlogger$redirect(ServerPlayer player, boolean enabled, Operation<Void> original) {
        var action = FtbTeamsAudit.redirect(this, player); boolean success = false;
        try { original.call(player, enabled); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
