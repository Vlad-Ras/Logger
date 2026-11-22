package com.roften.avilixlogger.smoke;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Minimal common-only entry for proving player-dependent recovery without optional Create classes. */
@EventBusSubscriber(modid="avilixlogger")
public final class PlayerRecoveryEntrySmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) throws Exception {
        if(!Boolean.getBoolean("avilixlogger.playerRecoverySmoke"))return;
        var mode=System.getProperty("avilixlogger.recoveryCrash","players");
        if(mode.equals("players")){PlayerTransferNativeSmoke.run(event.getServer().overworld());event.getServer().halt(false);}
        else PlayerTransferNativeSmoke.process(event.getServer().overworld(),mode);
    }
}
