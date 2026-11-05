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
import com.roften.avilixlogger.compat.ftbteams.FtbPropertyOwner;
@Pseudo
@Mixin(targets = "dev.ftb.mods.ftbteams.data.TeamPropertyCollectionImpl", remap = false)
public abstract class PropertiesMixin implements FtbPropertyOwner {
    @Unique private Object avilixlogger$team;
    public Object avilixlogger$owner() { return avilixlogger$team; }
    public void avilixlogger$owner(Object team) { avilixlogger$team = team; }
    @WrapMethod(method = "set(Ldev/ftb/mods/ftbteams/api/property/TeamProperty;Ljava/lang/Object;)V", remap = false)
    private void avilixlogger$set(@Coerce Object property, Object value, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PROPERTIESMIXIN_SET.enabled()) { original.call(property, value); return; }
        var action = FtbTeamsAudit.properties(this, "direct-properties-set"); boolean success = false;
        try { original.call(property, value); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "updateFrom(Ldev/ftb/mods/ftbteams/api/property/TeamPropertyCollection;)V", remap = false)
    private void avilixlogger$update(@Coerce Object properties, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PROPERTIESMIXIN_UPDATE.enabled()) { original.call(properties); return; }
        var action = FtbTeamsAudit.properties(this, "direct-properties-update"); boolean success = false;
        try { original.call(properties); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "read(Lnet/minecraft/nbt/CompoundTag;)V", remap = false)
    private void avilixlogger$read(CompoundTag tag, Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PROPERTIESMIXIN_READ.enabled()) { original.call(tag); return; }
        var action = FtbTeamsAudit.properties(this, "direct-properties-read"); boolean success = false;
        try { original.call(tag); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "collectProperties()V", remap = false)
    private void avilixlogger$recollect(Operation<Void> original) {
        if (!CaptureHandlers.FTBTEAMS_PROPERTIESMIXIN_RECOLLECT.enabled()) { original.call(); return; }
        var action = FtbTeamsAudit.properties(this, "direct-properties-recollect"); boolean success = false;
        try { original.call(); success = true; } finally { if (action != null) action.finish(!success); }
    }
}
