package com.roften.avilixlogger.core;

import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Release expired confirmation payloads even when nobody opens the GUI again. */
public final class PreviewMaintenance {
    private PreviewMaintenance() {}
    public static void onTick(ServerTickEvent.Post event) {
        if(event.getServer().getTickCount()%20!=0)return;
        long now=System.currentTimeMillis();
        RollbackPlanManager.expire(now);
        if(CreateSupport.available()){
            com.roften.avilixlogger.compat.create.CartItemRecovery.expirePreviews(now);
            com.roften.avilixlogger.compat.create.CartRollbackCoordinator.expirePreviews(now);
            com.roften.avilixlogger.compat.create.CreateTransferRollbackCoordinator.expirePreviews(now);
            com.roften.avilixlogger.compat.create.CreateStructuralRollbackCoordinator.expirePreviews(now);
            com.roften.avilixlogger.compat.create.CreateCouplingRollbackCoordinator.expirePreviews(now);
        }
        if(com.roften.avilixlogger.compat.backpacked.BackpackSupport.available())
            com.roften.avilixlogger.compat.backpacked.BackpackRecovery.expirePreviews(now);
    }
}
