package com.roften.avilixlogger.smoke;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

@EventBusSubscriber(modid="avilixlogger")
public final class Ae2AbsentSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if (!Boolean.getBoolean("avilixlogger.ae2Absent")) return;
        var log = org.slf4j.LoggerFactory.getLogger("AvilixLogger/AE2Smoke");
        try {
            if (net.neoforged.fml.ModList.get().isLoaded("ae2")) throw new AssertionError("AE2 was not absent");
            log.info("AE2_ABSENT_NATIVE_OK: server started without optional AE2 or its audit targets");
        } catch (Throwable failure) { log.error("AE2_NATIVE_FAILED", failure); }
        finally { event.getServer().halt(false); }
    }
}
