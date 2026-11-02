package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.core.CauseContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import java.util.*;
import java.util.function.*;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Actual OPAC packet -> queued area task -> later tick; identity only, no AuthCore override. */
final class OpenPacActorNativeSmoke {
    static void run(MinecraftServer server) throws Exception {
        Class<?> api = Class.forName("xaero.pac.common.server.api.OpenPACServerAPI");
        Object manager = api.getMethod("getServerClaimsManager").invoke(api.getMethod("get", MinecraftServer.class).invoke(null, server));
        Object data = Class.forName("xaero.pac.common.server.ServerData").getMethod("from", MinecraftServer.class).invoke(null, server);
        Class<?> dataClass = Class.forName("xaero.pac.common.server.IServerData");
        Object queue = manager.getClass().getMethod("getAreaClaimActionTaskHandler").invoke(manager);
        var tick = queue.getClass().getMethod("onTick", dataClass);
        var dim = server.overworld().dimension().location();
        var owner = (UUID) Class.forName("xaero.pac.common.server.player.config.PlayerConfig").getField("SERVER_CLAIM_UUID").get(null);
        var player = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "ClaimInitiator"), ClientInformation.createDefault());
        var unrelated = new ServerPlayer(server, server.overworld(), new GameProfile(UUID.randomUUID(), "OtherInitiator"), ClientInformation.createDefault());
        Class<?> packetType = Class.forName("xaero.pac.common.packet.type.PacketType");
        Class<?> receiverClass = Class.forName("xaero.pac.common.packet.ServerPacketReceiver");
        Object receiver = receiverClass.getConstructors()[0].newInstance(new Object[]{null});
        var getTask = receiverClass.getDeclaredMethod("getTask", packetType, Object.class, ServerPlayer.class); getTask.setAccessible(true);
        var seen = new ArrayList<CauseContext.Cause>();
        var claim = manager.getClass().getMethod("tryToClaimArea", ResourceLocation.class, UUID.class, int.class, ResourceLocation.class, int.class, int.class, int.class, int.class, int.class, int.class, boolean.class, Consumer.class);
        var unclaim = manager.getClass().getMethod("tryToUnclaimArea", ResourceLocation.class, UUID.class, ResourceLocation.class, int.class, int.class, int.class, int.class, int.class, int.class, boolean.class, Consumer.class);
        for (String action : List.of("claim", "unclaim", "background")) {
            int before = seen.size();
            Consumer<Object> completed = result -> seen.add(CauseContext.peek());
            Runnable submit = () -> {
                try {
                    if (action.equals("unclaim")) unclaim.invoke(manager, dim, owner, dim, 4, 4, 4, 4, 5, 4, true, completed);
                    else claim.invoke(manager, dim, owner, 0, dim, 4, 4, 4, 4, 5, 4, true, completed);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
            };
            if (action.equals("background")) submit.run();
            else {
                BiConsumer<Object, ServerPlayer> handler = (packet, actor) -> submit.run();
                Object type = packetType.getConstructors()[0].newInstance(0, Object.class, null, null, handler, null);
                ((Runnable)getTask.invoke(receiver, type, new Object(), player)).run();
            }
            check(CauseContext.peek() == null, "request actor leaked out of packet");
            check(seen.size() == before, "area task fixture did not defer to a later tick");
            try (var scope = CauseContext.push(unrelated, CauseContext.Kind.OTHER, unrelated.blockPosition(), ItemStack.EMPTY)) {
                for (int attempt = 0; seen.size() == before && attempt < 20; attempt++) tick.invoke(queue, data);
                check(CauseContext.peek().actorUuid().equals(unrelated.getUUID()), "task scope damaged outer actor");
            }
            check(seen.size() == before + 1, "native area task did not complete: " + action);
            var actor = seen.getLast();
            if (action.equals("background")) check(actor == null, "background task inherited another player's identity");
            else check(actor != null && actor.actorUuid().equals(player.getUUID()) && actor.actorName().equals("ClaimInitiator") && !actor.actorUuid().equals(owner), "lost actual claim/unclaim actor: " + actor);
            Object nativeClaim = manager.getClass().getMethod("get", ResourceLocation.class, int.class, int.class).invoke(manager, dim, 4, 4);
            if (action.equals("unclaim")) check(nativeClaim == null, "native unclaim did not mutate chunk");
            else check(nativeClaim != null && nativeClaim.getClass().getMethod("getPlayerId").invoke(nativeClaim).equals(owner), "claim owner confused with initiating actor");
        }
        for (int x = 4; x <= 5; x++) manager.getClass().getMethod("unclaim", ResourceLocation.class, int.class, int.class).invoke(manager, dim, x, 4);
        check(CauseContext.peek() == null, "task actor leaked");
        check(!com.roften.avilixlogger.LoggerConfig.isEnabled(), "identity regression must not enable authenticated publication");
        System.out.println("OPAC_ACTOR_NATIVE_OK: native packets, deferred multi-chunk claim/unclaim, caller distinct from owner, unrelated tick caller, SYSTEM background; AuthCore publication remains disabled");
    }
}
