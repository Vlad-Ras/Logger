package com.roften.avilixlogger.smoke;

import com.mojang.authlib.GameProfile;
import com.roften.avilixlogger.client.gui.LogViewerClientHooks;
import com.roften.avilixlogger.net.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.*;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.*;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.ServerLinks;
import net.minecraft.world.flag.FeatureFlags;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Exercise the real client hook before Minecraft.player exists, with connection-bound payload capture. */
final class ClientReportConnectionSmoke {
    private static Fixture abandoned, current;
    private static Field inventoryField;
    private static Object originalInventory;
    private static final class Wire extends Connection {
        PacketListener listener; boolean active = true;
        Wire() { super(PacketFlow.CLIENTBOUND); }
        @Override public PacketListener getPacketListener() { return listener; }
        @Override public boolean isConnected() { return active; }
    }
    private static final class Listener extends ClientPacketListener {
        Listener(Minecraft client, Wire wire) {
            super(client, wire, new CommonListenerCookie(new GameProfile(UUID.randomUUID(), "ReportFixture"), null,
                    RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), FeatureFlags.DEFAULT_FLAGS,
                    null, null, null, Map.of(), null, false, Map.of(), ServerLinks.EMPTY, ConnectionType.NEOFORGE));
        }
        @Override public boolean hasChannel(CustomPacketPayload.Type<?> type) { return true; }
    }
    private record Fixture(Wire wire, List<CustomPacketPayload> sent, IPayloadContext context) {
        static Fixture create(Minecraft client) {
            var wire = new Wire(); wire.listener = new Listener(client, wire);
            List<CustomPacketPayload> packets = new CopyOnWriteArrayList<>();
            IPayloadContext context = (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(),
                    new Class<?>[]{IPayloadContext.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "connection" -> wire;
                        case "listener" -> wire.listener;
                        case "reply" -> { packets.add((CustomPacketPayload) args[0]); yield null; }
                        case "toString" -> "ReportFixtureContext";
                        default -> throw new AssertionError("Unexpected payload context call: " + method.getName());
                    });
            return new Fixture(wire, packets, context);
        }
        ClientReportPayloads.Report base() {
            return sent.stream().filter(ClientReportPayloads.Report.class::isInstance)
                    .map(ClientReportPayloads.Report.class::cast).findFirst().orElseThrow(() -> new AssertionError("request dropped before player creation"));
        }
    }
    static void start(Minecraft client) throws Exception {
        if (client.player != null || client.getConnection() != null) throw new AssertionError("fixture must run before player/world connection");
        inventoryField = LogViewerClientHooks.class.getDeclaredField("modInventory"); inventoryField.setAccessible(true);
        originalInventory = inventoryField.get(null);
        var blocked = new CompletableFuture<List<ClientReportPayloads.Mod>>(); inventoryField.set(null, blocked);
        abandoned = Fixture.create(client); current = Fixture.create(client);
        LogViewerClientHooks.acceptClientReportRequest(new ClientReportPayloads.Request(8001), abandoned.context());
        LogViewerClientHooks.acceptClientReportRequest(new ClientReportPayloads.Request(8002), current.context());
        var first = abandoned.base(); var second = current.base();
        if (first.mods().isEmpty() || second.mods().isEmpty() || second.id() != 8002) throw new AssertionError("metadata missing before hashing");
        if (current.sent().stream().anyMatch(ClientReportHashesPayload.class::isInstance)) throw new AssertionError("hashes completed before fixture release");
        abandoned.wire().active = false;
        blocked.complete(second.mods().stream().map(mod -> new ClientReportPayloads.Mod(mod.id(), mod.version(), mod.displayName(), "ab".repeat(32))).toList());
        System.out.println("CLIENT_REPORT_EARLY_CONNECTION_BASE_OK: real hook replied twice with Minecraft.player=null while hashes were blocked");
    }
    static void verify() throws Exception {
        try {
            if (abandoned.sent().stream().anyMatch(ClientReportHashesPayload.class::isInstance)) throw new AssertionError("hashes sent to disconnected request");
            var assembly = new ClientReportHashesPayload.Assembly(current.base());
            current.sent().stream().filter(ClientReportHashesPayload.class::isInstance)
                    .map(ClientReportHashesPayload.class::cast).forEach(assembly::accept);
            if (!assembly.complete() || assembly.report().mods().stream().anyMatch(mod -> mod.sha256().isEmpty()))
                throw new AssertionError("active connection did not receive hash supplement");
            System.out.println("CLIENT_REPORT_EARLY_CONNECTION_OK: metadata before player; blocked hashes; late completion discarded for disconnected peer; active peer completed");
        } finally {
            inventoryField.set(null, originalInventory);
            abandoned.wire().active = false; current.wire().active = false;
            abandoned = null; current = null; originalInventory = null;
        }
    }
}
