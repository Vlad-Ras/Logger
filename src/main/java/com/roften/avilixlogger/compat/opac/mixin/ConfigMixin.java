package com.roften.avilixlogger.compat.opac.mixin;

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
@Mixin(targets = "xaero.pac.common.server.player.config.PlayerConfig", remap = false)
public abstract class ConfigMixin {
    @WrapMethod(method = "forceSet(Lxaero/pac/common/server/player/config/api/v2/IPlayerConfigOptionSpecAPI;Ljava/lang/Object;)V", remap = false)
    private void avilixlogger$forceSet(@Coerce Object option, Object value, Operation<Void> original) {
        var action = OpenPacStateAudit.option(this, option, "config-forceSet"); boolean success = false;
        try { original.call(option, value); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "tryToSet(Lxaero/pac/common/server/player/config/api/v2/IPlayerConfigOptionSpecAPI;Ljava/lang/Object;)Lxaero/pac/common/server/player/config/api/v2/IPlayerConfigAPI$SetResult;", remap = false)
    private Object avilixlogger$trySet(@Coerce Object option, Object value, Operation<Object> original) {
        var action = OpenPacStateAudit.option(this, option, "config-trySet"); boolean success = false;
        try { var result = original.call(option, value); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setStorage(Lcom/electronwill/nightconfig/core/Config;)V", remap = false)
    private void avilixlogger$storage(@Coerce Object storage, Operation<Void> original) {
        var action = OpenPacStateAudit.storage(this); boolean success = false;
        try { original.call(storage); success = true; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "createSubConfig(Ljava/lang/String;IZ)Lxaero/pac/common/server/player/config/sub/PlayerSubConfig;", remap = false)
    private Object avilixlogger$createSub(String id, int index, boolean init, Operation<Object> original) {
        var action = OpenPacStateAudit.config(this, "config-createSub"); boolean success = false;
        try { var result = original.call(id, index, init); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
    @Coerce
    @WrapMethod(method = "removeSubConfig(Ljava/lang/String;)Lxaero/pac/common/server/player/config/sub/PlayerSubConfig;", remap = false)
    private Object avilixlogger$removeSub(String id, Operation<Object> original) {
        var action = OpenPacStateAudit.config(this, "config-removeSub"); boolean success = false;
        try { var result = original.call(id); success = true; return result; }
        finally { if (action != null) action.finish(!success); }
    }
}
