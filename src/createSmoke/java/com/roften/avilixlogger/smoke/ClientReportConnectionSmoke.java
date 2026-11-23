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
    private static Fixture real;
    private static long realStarted;
    static void startReal(Minecraft client) throws Exception {
        inventoryField.set(null, null); // cold inventory: exercise the real ModList and real archive providers
        real = Fixture.create(client); realStarted = System.nanoTime();
        LogViewerClientHooks.acceptClientReportRequest(new ClientReportPayloads.Request(8100), real.context());
        if (real.base().mods().isEmpty()) throw new AssertionError("real inventory metadata missing");
        var providers = new java.util.TreeMap<String,Integer>();
        for (var mod : net.neoforged.fml.ModList.get().getMods()) {
            var owner=mod.getOwningFile(); if(owner==null||owner.getFile()==null)continue;
            var path=owner.getFile().getFilePath(); if(path!=null)providers.merge(path.getFileSystem().provider().getScheme(),1,Integer::sum);
        }
        System.out.println("CLIENT_REPORT_REAL_HASH_STARTED providers="+providers+" initial_mods="+real.base().mods().size());
    }
    static void verifyReal() {
        var assembly=new ClientReportHashesPayload.Assembly(real.base());
        real.sent().stream().filter(ClientReportHashesPayload.class::isInstance).map(ClientReportHashesPayload.class::cast).forEach(assembly::accept);
        if(!assembly.complete()||assembly.failed())throw new AssertionError("real ModList/JarJar hash collection failed or stalled");
        var result=assembly.report();
        for(var mod : net.neoforged.fml.ModList.get().getMods()) {
            var owner=mod.getOwningFile();if(owner==null||owner.getFile()==null)continue;
            var path=owner.getFile().getFilePath();
            if(path!=null&&java.nio.file.Files.isRegularFile(path)) {
                var entry=result.mods().stream().filter(row->row.id().equals(mod.getModId())).findFirst().orElse(null);
                if(entry!=null&&entry.sha256().isEmpty())throw new AssertionError("readable real mod has no SHA-256: "+mod.getModId()+" provider="+path.getFileSystem().provider().getScheme());
            }
        }
        long hashed=result.mods().stream().filter(mod->!mod.sha256().isEmpty()).count();
        if(hashed==0)throw new AssertionError("no real mod JARs hashed");
        System.out.println("CLIENT_REPORT_REAL_HASH_OK mods="+result.mods().size()+" hashed="+hashed+" elapsed_ms="+(System.nanoTime()-realStarted)/1_000_000);
        real.wire().active=false;real=null;
    }

}
