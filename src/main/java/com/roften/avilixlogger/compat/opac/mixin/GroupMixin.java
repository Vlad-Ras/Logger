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
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
@Pseudo
@Mixin(targets = "xaero.pac.common.server.player.config.group.custom.CustomPlayerConfigGroup", remap = false)
public abstract class GroupMixin {
    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void avilixlogger$owner(CallbackInfo ci) { OpenPacStateAudit.bindGroup(this); }
    @Coerce
    @WrapMethod(method = "includeMemberInternal(Ljava/util/UUID;Ljava/lang/String;Z)Lcom/mojang/datafixers/util/Either;", remap = false)
    private Object avilixlogger$includeMember(UUID id, String name, boolean sync, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_GROUPMIXIN_INCLUDEMEMBER.enabled()) { return original.call(id, name, sync); }
        var action = OpenPacStateAudit.group(this, false, "group-includeMember"); boolean success = false;
        try { var result = original.call(id, name, sync); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "excludeMemberInternal(Lxaero/pac/common/player/config/group/custom/ICustomPlayerGroupMember;)Ljava/util/Optional;", remap = false)
    private Optional<?> avilixlogger$excludeMember(@Coerce Object member, Operation<Optional<?>> original) {
        if (!CaptureHandlers.OPAC_GROUPMIXIN_EXCLUDEMEMBER.enabled()) { return original.call(member); }
        var action = OpenPacStateAudit.group(this, false, "group-excludeMember"); boolean success = false;
        try { var result = original.call(member); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "includeGroupInternal(Ljava/lang/String;Z)Ljava/util/Optional;", remap = false)
    private Optional<?> avilixlogger$includeGroup(String id, boolean sync, Operation<Optional<?>> original) {
        if (!CaptureHandlers.OPAC_GROUPMIXIN_INCLUDEGROUP.enabled()) { return original.call(id, sync); }
        var action = OpenPacStateAudit.group(this, false, "group-includeGroup"); boolean success = false;
        try { var result = original.call(id, sync); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "excludeGroup(Ljava/lang/String;)Ljava/util/Optional;", remap = false)
    private Optional<?> avilixlogger$excludeGroup(String id, Operation<Optional<?>> original) {
        if (!CaptureHandlers.OPAC_GROUPMIXIN_EXCLUDEGROUP.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.group(this, false, "group-excludeGroup"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
