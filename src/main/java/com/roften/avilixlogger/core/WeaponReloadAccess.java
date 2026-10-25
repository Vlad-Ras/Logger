package com.roften.avilixlogger.core;

/** One ephemeral reload per connected player, released on native stop or player disposal. */
public interface WeaponReloadAccess {
    WeaponCombatAudit.ReloadSession avilixlogger$reload();
    void avilixlogger$reload(WeaponCombatAudit.ReloadSession session);
}
