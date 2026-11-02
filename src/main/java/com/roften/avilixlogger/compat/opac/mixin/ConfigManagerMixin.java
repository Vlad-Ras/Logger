package com.roften.avilixlogger.compat.opac.mixin;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.roften.avilixlogger.compat.opac.OpenPacStateAudit;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.Coerce;
@Pseudo
@Mixin(targets = "xaero.pac.common.server.player.config.PlayerConfigManager", remap = false)
public abstract class ConfigManagerMixin {
    @WrapMethod(method = "setDefaultConfig(Lxaero/pac/common/server/player/config/PlayerConfig;)V", remap = false)
    private void avilixlogger$defaultConfig(@Coerce Object config, Operation<Void> original) {
        var action = OpenPacStateAudit.configManager(this, "defaultConfig"); boolean success = false;
        try { original.call(config); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setWildernessConfig(Lxaero/pac/common/server/player/config/PlayerConfig;)V", remap = false)
    private void avilixlogger$wildernessConfig(@Coerce Object config, Operation<Void> original) {
        var action = OpenPacStateAudit.configManager(this, "wildernessConfig"); boolean success = false;
        try { original.call(config); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setServerClaimConfig(Lxaero/pac/common/server/player/config/PlayerConfig;)V", remap = false)
    private void avilixlogger$serverClaimConfig(@Coerce Object config, Operation<Void> original) {
        var action = OpenPacStateAudit.configManager(this, "serverClaimConfig"); boolean success = false;
        try { original.call(config); success = true; } finally { if (action != null) action.finish(!success); }
    }
    @WrapMethod(method = "setExpiredClaimConfig(Lxaero/pac/common/server/player/config/PlayerConfig;)V", remap = false)
    private void avilixlogger$expiredClaimConfig(@Coerce Object config, Operation<Void> original) {
        var action = OpenPacStateAudit.configManager(this, "expiredClaimConfig"); boolean success = false;
        try { original.call(config); success = true; } finally { if (action != null) action.finish(!success); }
    }
}
