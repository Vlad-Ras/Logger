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
@Mixin(targets = "dev.ftb.mods.ftbteams.data.AbstractTeamBase", remap = false)
public abstract class TeamBaseMixin {
    @WrapMethod(method = "setProperty(Ldev/ftb/mods/ftbteams/api/property/TeamProperty;Ljava/lang/Object;)V", remap = false)
    private void avilixlogger$set_property(@Coerce Object property, Object value, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "set_property"); boolean success = false;
        try { original.call(property, value); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addMember(Ljava/util/UUID;Ldev/ftb/mods/ftbteams/api/TeamRank;)V", remap = false)
    private void avilixlogger$add_member(UUID id, @Coerce Object rank, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "add_member"); boolean success = false;
        try { original.call(id, rank); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeMember(Ljava/util/UUID;)V", remap = false)
    private void avilixlogger$remove_member(UUID id, Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "remove_member"); boolean success = false;
        try { original.call(id); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "invalidateTeam()V", remap = false)
    private void avilixlogger$invalidate(Operation<Void> original) {
        var action = FtbTeamsAudit.team(this, "invalidate"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "addMessages(Ljava/util/Collection;)V", remap = false)
    private void avilixlogger$messages(Collection<?> messages, Operation<Void> original) {
        var action = FtbTeamsAudit.historyChange(this); boolean success = false;
        try { original.call(messages); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
