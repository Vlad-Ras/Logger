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
@Mixin(targets = "xaero.pac.common.server.player.config.group.ServerPlayerConfigGroupManager", remap = false)
public abstract class GroupManagerMixin {
    @Coerce
    @WrapMethod(method = "addData(Ljava/lang/String;)Lcom/mojang/datafixers/util/Either;", remap = false)
    private Object avilixlogger$add(String id, Operation<Object> original) {
        if (!CaptureHandlers.OPAC_GROUPMANAGERMIXIN_ADD.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.group(this, true, "group-add"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeCustom(Ljava/lang/String;)Ljava/util/Optional;", remap = false)
    private Optional<?> avilixlogger$remove(String id, Operation<Optional<?>> original) {
        if (!CaptureHandlers.OPAC_GROUPMANAGERMIXIN_REMOVE.enabled()) { return original.call(id); }
        var action = OpenPacStateAudit.group(this, true, "group-remove"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "removeAll()V", remap = false)
    private void avilixlogger$removeAll(Operation<Void> original) {
        if (!CaptureHandlers.OPAC_GROUPMANAGERMIXIN_REMOVEALL.enabled()) { original.call(); return; }
        var action = OpenPacStateAudit.group(this, true, "group-removeAll"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "reset()V", remap = false)
    private void avilixlogger$reset(Operation<Void> original) {
        if (!CaptureHandlers.OPAC_GROUPMANAGERMIXIN_RESET.enabled()) { original.call(); return; }
        var action = OpenPacStateAudit.group(this, true, "group-reset"); boolean success = false;
        try { original.call(); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
}
