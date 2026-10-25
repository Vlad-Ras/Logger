package com.roften.avilixlogger.compat.scguns;

import com.mrcrayfish.framework.api.sync.SyncedDataKey;
import com.roften.avilixlogger.core.*;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;

/** Observe only an existing native write, never poll Framework data on server ticks. */
public final class ReloadStateAudit {
    private ReloadStateAudit() {}
    @SuppressWarnings("unchecked")
    public static Boolean before(SyncedDataKey key,Entity entity) {
        if(!(entity instanceof ServerPlayer p) || !p.server.isSameThread() || !WeaponCombatAudit.enabled() || !CaptureTypePolicy.enabled(4))return null;
        try {if(key.id().getNamespace().equals("scguns") && key.id().getPath().equals("reloading") && key.getValue(entity) instanceof Boolean value)return value;}
        catch(RuntimeException failure){WeaponCombatAudit.failure();}return null;
    }
    @SuppressWarnings("unchecked")
    public static void after(SyncedDataKey key,Entity entity,Boolean before) {
        if(before==null)return;
        try {if(key.getValue(entity) instanceof Boolean value)WeaponCombatAudit.reloadState((Player)entity,before,value);}
        catch(RuntimeException failure){WeaponCombatAudit.failure();}
    }
}
