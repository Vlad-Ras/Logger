package com.roften.avilixlogger.compat.scguns.mixin;

import com.roften.avilixlogger.core.*;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.*;

@Mixin(value=ServerPlayer.class,remap=false)
public abstract class PlayerReloadTraceMixin implements WeaponReloadAccess {
    @Unique private WeaponCombatAudit.ReloadSession avilixlogger$reloadSession;
    public WeaponCombatAudit.ReloadSession avilixlogger$reload(){return avilixlogger$reloadSession;}
    public void avilixlogger$reload(WeaponCombatAudit.ReloadSession session){avilixlogger$reloadSession=session;}
}
