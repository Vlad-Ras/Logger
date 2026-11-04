package com.roften.avilixlogger.core;

/** Attached to supported projectiles; no global projectile map or polling. */
public interface WeaponTraceAccess {
    WeaponCombatTrace.Shot avilixlogger$shot();
    void avilixlogger$shot(WeaponCombatTrace.Shot shot);
}
