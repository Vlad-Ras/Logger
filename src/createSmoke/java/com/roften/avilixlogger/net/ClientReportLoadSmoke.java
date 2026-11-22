package com.roften.avilixlogger.net;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.Unpooled;
import com.roften.avilixlogger.core.LoggerServerData;
import net.minecraft.commands.CommandSource;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Measures report work in isolation on the native server, outside the regular tick loop. */
public final class ClientReportLoadSmoke {
    private static final int WARMUP = 750;
    private static final int SAMPLES = 2_000;

    private ClientReportLoadSmoke() {}

    public static void run(MinecraftServer server) {
        var mods = IntStream.range(0, 512)
                .mapToObj(i -> new ClientReportPayloads.Mod("fixture_mod_" + i + "_" + "m".repeat(60), "1.2.3+fixture", "Тестовый мод " + i))
                .toList();
        var packs = IntStream.range(0, 24).mapToObj(i -> "file/fixture-pack-" + i + ".zip").toList();
        var report = ClientReportPayloads.collect(1, mods, packs);
        checkNetworkSizes(server);
        checkHashPackets(server);
        checkReportIoIsolation();
        checkReplyReplacement(server);
        checkDailyLimit(server);
        checkStructuredReport(server);
        checkConsoleStatus(server, report);
        var inbound = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        AtomicInteger printedCharacters = new AtomicInteger();
        var gui = server.createCommandSourceStack().withSource(new GuiCommandOutput(message -> {
            var reply = S2CLoggerActionResultPayload.from(1, message);
            if (!reply.links().isEmpty() || reply.message().contains("сообщение сокращено")) {
                throw new AssertionError("Client report GUI lost part of the full report");
            }
            printedCharacters.addAndGet(reply.message().length());
        }));
        try {
            ClientReportPayloads.Report.CODEC.encode(inbound, report);
            if (!report.truncated() || inbound.readableBytes() < 26 * 1024 || inbound.readableBytes() > 30 * 1024) {
                throw new AssertionError("Load probe did not exercise a near-limit client report");
            }
            int wireBytes = inbound.readableBytes();
            Runnable decode = () -> {
                inbound.readerIndex(0);
                if (ClientReportPayloads.Report.CODEC.decode(inbound).mods().size() != report.mods().size()) {
                    throw new AssertionError("Client report lost mods during decode");
                }
            };
            Runnable format = () -> ClientReportNetwork.writeReport(gui, "AuditFixture", Instant.EPOCH, report);
            measure("decode", wireBytes, report.mods().size(), decode);
            measure("gui-format", wireBytes, report.mods().size(), format);
            if (printedCharacters.get() < wireBytes * SAMPLES) throw new AssertionError("GUI received incomplete report");
        } finally {
            inbound.release();
        }
    }

    @SuppressWarnings("unchecked")
    private static void checkReplyReplacement(MinecraftServer server) {
        try {
            var replyType=Class.forName(ClientReportNetwork.class.getName()+"$Reply");
            var replyConstructor=replyType.getDeclaredConstructor(net.minecraft.commands.CommandSourceStack.class,Long.class);replyConstructor.setAccessible(true);
            var oldReply=replyConstructor.newInstance(server.createCommandSourceStack(),Long.valueOf(100));
            var newReply=replyConstructor.newInstance(server.createCommandSourceStack(),Long.valueOf(101));
            var pendingType=Class.forName(ClientReportNetwork.class.getName()+"$Pending");
            var pendingConstructor=pendingType.getDeclaredConstructor(long.class,net.minecraft.server.network.ServerGamePacketListenerImpl.class,replyType,boolean.class);pendingConstructor.setAccessible(true);
            var target=net.neoforged.neoforge.common.util.FakePlayerFactory.get(server.overworld(),new com.mojang.authlib.GameProfile(UUID.randomUUID(),"ReportRoute"));
            var pending=pendingConstructor.newInstance(42L,target.connection,oldReply,false);
            var field=ClientReportNetwork.class.getDeclaredField("PENDING");field.setAccessible(true);
            var map=(java.util.Map<UUID,Object>)field.get(null);Object previous=map.put(target.getUUID(),pending);
            try {
                var join=ClientReportNetwork.class.getDeclaredMethod("joinPending",net.minecraft.server.level.ServerPlayer.class,pendingType,replyType);join.setAccessible(true);
                join.invoke(null,target,pending,newReply);
                var reply=pendingType.getDeclaredMethod("reply");reply.setAccessible(true);
                if(reply.invoke(map.get(target.getUUID()))!=newReply)throw new AssertionError("repeated GUI refresh kept stale reply ID");
            }finally{if(previous==null)map.remove(target.getUUID());else map.put(target.getUUID(),previous);}
            System.out.println("CLIENT_REPORT_REPLY_REPLACEMENT_OK: second GUI request from same administrator replaces stale request ID while collection continues");
        }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
    }

    private static void checkReportIoIsolation() {
        var blocked = new java.util.concurrent.CountDownLatch(2);
        var release = new java.util.concurrent.CountDownLatch(1);
        var written = new java.util.concurrent.CountDownLatch(1);
        try {
            for (int i=0;i<2;i++) LoggerNetwork.executeClientReportRead(() -> {
                blocked.countDown();
                try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            if (!blocked.await(2, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("report read pool did not start");
            LoggerNetwork.executeClientReportIo(written::countDown);
            if (!written.await(1, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("slow cached reads blocked report writes");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        finally { release.countDown(); }
        System.out.println("CLIENT_REPORT_IO_ISOLATION_OK: two blocked cached reads do not delay a new write");
    }
    private static void checkHashPackets(MinecraftServer server) {
        var mods = new ArrayList<ClientReportPayloads.Mod>(); var packs = new ArrayList<String>();
        var hashes = new java.util.LinkedHashMap<String,String>();
        for(int i=0;i<512;i++) mods.add(new ClientReportPayloads.Mod("m"+i,"1","", "ab".repeat(32)));
        for(int i=0;i<128;i++){String pack="file/"+i+"x".repeat(100);packs.add(pack);hashes.put(pack,"cd".repeat(32));}
        var base=ClientReportPayloads.collect(42,mods.stream().map(m->new ClientReportPayloads.Mod(m.id(),m.version())).toList(),packs);
        var parts=ClientReportHashesPayload.split(base,mods,hashes);
        var assembly=new ClientReportHashesPayload.Assembly(base);
        int total=0,maximum=0;
        var buf=new RegistryFriendlyByteBuf(Unpooled.buffer(),server.registryAccess());
        try {
            for(var part:parts){
                buf.clear();ClientReportHashesPayload.CODEC.encode(buf,part);
                int bytes=buf.readableBytes();maximum=Math.max(maximum,bytes);total+=bytes;
                if(bytes>30*1024)throw new AssertionError("hash packet exceeds serverbound limit");
                var decoded=ClientReportHashesPayload.CODEC.decode(buf);
                if(!part.equals(decoded)||buf.isReadable())throw new AssertionError("hash packet roundtrip failed");
                assembly.accept(decoded);
            }
            buf.clear();
            var view=new ClientReportViewPayloads.View(71,"Отчёт сохранён","Fixture",123,List.of(),List.of(),assembly.report());
            ClientReportViewPayloads.FullView.CODEC.encode(buf,new ClientReportViewPayloads.FullView(view));
            if(!view.equals(ClientReportViewPayloads.FullView.CODEC.decode(buf).view()))throw new AssertionError("enriched view lost inventory beyond 30 KiB");
            try {
                var encode=com.roften.avilixlogger.core.ClickHouseLogStorage.class.getDeclaredMethod("encodeClientReport",ClientReportPayloads.Report.class);encode.setAccessible(true);
                var decode=com.roften.avilixlogger.core.ClickHouseLogStorage.class.getDeclaredMethod("decodeClientReport",String.class);decode.setAccessible(true);
                String json=(String)encode.invoke(null,assembly.report());
                var restored=(ClientReportPayloads.Report)decode.invoke(null,json);
                if(!restored.mods().equals(assembly.report().mods()) || !restored.packs().equals(assembly.report().packs())
                        || !restored.packHashes().equals(assembly.report().packHashes()))throw new AssertionError("stored enriched report lost data");
                System.out.println("CLIENT_REPORT_HASH_STORAGE_CODEC_OK bytes="+json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }catch(ReflectiveOperationException failure){throw new AssertionError(failure);}
            final int wireBytes=total;
            measure("hash-merge",wireBytes,base.mods().size(),()->{
                var next=new ClientReportHashesPayload.Assembly(base);parts.forEach(next::accept);
                if(next.report().mods().size()!=512)throw new AssertionError("hash merge lost inventory");
            });
            System.out.printf(Locale.ROOT,"CLIENT_REPORT_HASH_WIRE_OK parts=%d maximum_bytes=%d total_hash_bytes=%d view_bytes=%d mods=%d packs=%d%n",parts.size(),maximum,total,buf.writerIndex(),assembly.report().mods().size(),assembly.report().packs().size());
        }finally{buf.release();}
    }

    private static void checkNetworkSizes(MinecraftServer server) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            var markers = IntStream.range(0, S2CRollbackPreviewPayload.MAX_MARKERS)
                    .mapToObj(i -> new S2CRollbackPreviewPayload.Marker(new net.minecraft.core.BlockPos(i, 64, i),
                            S2CRollbackPreviewPayload.Kind.BLOCK, "minecraft:stone")).toList();
            var preview = new S2CRollbackPreviewPayload(true, "fixture", "minecraft:overworld",
                    0, 0, 0, 512, 255, 512, 0, Long.MAX_VALUE, markers);
            S2CRollbackPreviewPayload.STREAM_CODEC.encode(buffer, preview);
            int previewBytes = buffer.readableBytes();
            if (!preview.equals(S2CRollbackPreviewPayload.STREAM_CODEC.decode(buffer))) throw new AssertionError("Preview changed on wire");
            buffer.clear();
            var alerts = new ClientReportAlertPayloads.View(true, IntStream.range(0, 32)
                    .mapToObj(i -> "Player · совпадений: " + i + " · resourcepack.zip").toList());
            var status = new ClientReportStatusPayloads.Status(42L, ClientReportStatusPayloads.Stage.COLLECTING);
            ClientReportStatusPayloads.Status.CODEC.encode(buffer, status);
            if (!status.equals(ClientReportStatusPayloads.Status.CODEC.decode(buffer)) || buffer.isReadable()) {
                throw new AssertionError("Client report status changed on wire");
            }
            buffer.clear();
            ClientReportAlertPayloads.View.CODEC.encode(buffer, alerts);
            int alertBytes = buffer.readableBytes();
            if (!alerts.equals(ClientReportAlertPayloads.View.CODEC.decode(buffer))) throw new AssertionError("Alerts changed on wire");
            buffer.clear();
            var chunk = new byte[ServerLogPayloads.CHUNK_BYTES];
            String diagnostics = com.roften.avilixlogger.core.ServerLogFiles.COLLISION_DIAGNOSTICS;
            ServerLogPayloads.View.CODEC.encode(buffer, ServerLogPayloads.View.data(2, diagnostics, 0, chunk.length, chunk));
            int exportBytes = buffer.readableBytes();
            var decodedLog = ServerLogPayloads.View.CODEC.decode(buffer);
            if (!Arrays.equals(chunk, decodedLog.data()) || !diagnostics.equals(decodedLog.name())) throw new AssertionError("Diagnostics name or data changed on wire");
            buffer.clear();
            var files = IntStream.range(0, ServerLogPayloads.MAX_ENTRIES)
                    .mapToObj(i -> new ServerLogPayloads.FileInfo(i == 0 ? diagnostics : "2026-11-21-"+i+".log.gz", i, 1L)).toList();
            ServerLogPayloads.View.CODEC.encode(buffer, ServerLogPayloads.View.list(3, files));
            if (!files.equals(ServerLogPayloads.View.CODEC.decode(buffer).files())) throw new AssertionError("Full log listing lost diagnostics entry");
            buffer.clear();
            C2SEntryTeleportPayload.STREAM_CODEC.encode(buffer, new C2SEntryTeleportPayload(2, 81));
            int teleportBytes = buffer.readableBytes();
            System.out.printf(Locale.ROOT, "LOGGER_PAYLOAD_PROFILE preview_512_bytes=%d alerts_32_bytes=%d log_chunk_bytes=%d teleport_request_bytes=%d%n",
                    previewBytes, alertBytes, exportBytes, teleportBytes);
        } finally {
            buffer.release();
        }
    }

    private static void checkDailyLimit(MinecraftServer server) {
        long now = System.currentTimeMillis();
        long day = java.time.Duration.ofHours(24).toMillis();
        var data = LoggerServerData.load(new CompoundTag(), server.registryAccess());
        UUID target = UUID.randomUUID();
        if (data.clientReportAlerts(target)) throw new AssertionError("Report alerts must be disabled by default");
        data.setClientReportAlerts(target, true);
        if (data.nextClientReportAt(target, now) != 0
                || data.nextClientReportAt(target, now + 1) != 0) {
            throw new AssertionError("Unconfirmed collection must not reserve the daily interval");
        }
        // An interrupted collection never blocks the next login or retry.
        var failedRestored = LoggerServerData.load(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
        if (failedRestored.nextClientReportAt(target, now + 1) != 0) {
            throw new AssertionError("Failed report was persisted as a success");
        }
        data.markClientReportSaved(target, now);
        if (data.nextClientReportAt(target, now + 1) != now + day) {
            throw new AssertionError("Confirmed collection must advance the daily interval");
        }
        var restored = LoggerServerData.load(data.save(new CompoundTag(), server.registryAccess()), server.registryAccess());
        if (!restored.clientReportAlerts(target)) throw new AssertionError("Report alert subscription lost on reload");
        restored.setClientReportAlerts(target, false);
        if (LoggerServerData.load(restored.save(new CompoundTag(), server.registryAccess()), server.registryAccess()).clientReportAlerts(target)) {
            throw new AssertionError("Disabling report alerts was not saved");
        }
        if (restored.nextClientReportAt(target, now + day - 1) != now + day
                || restored.nextClientReportAt(target, now + day) != 0) {
            throw new AssertionError("Daily report success interval did not survive world save and reload");
        }
        // 2.1.33 persisted request reservations as if the client had responded.
        // Ignore this legacy field when upgrading, then allow immediate collection.
        var legacy = new CompoundTag();
        var legacyEntries = new net.minecraft.nbt.ListTag();
        var legacyEntry = new CompoundTag();
        legacyEntry.putUUID("player", target);
        legacyEntry.putLong("until", now + day);
        legacyEntries.add(legacyEntry);
        legacy.put("clientReportCooldowns", legacyEntries);
        if (LoggerServerData.load(legacy, server.registryAccess()).nextClientReportAt(target, now) != 0) {
            throw new AssertionError("Old failed-request cooldown should not survive migration");
        }
        System.out.println("CLIENT_REPORT_DAILY_LIMIT_OK: acknowledged-only interval, failed request retry and migration");
    }

    private static void checkStructuredReport(MinecraftServer server) {
        var packs = java.util.List.of("file/Xray_Ultimate-v1.21.zip", "file/AntiXray.zip",
                "file/X-ray - Ultimate.zip", "file/Xray helper.zip", "file/Xray-v.zip", "file/Xray_3.zip",
                "file/Xray_Ultimate-v1.21.zip");
        String rules = "xray,x ray,xray ultimate,x ray ultimate";
        var matches = ClientReportNetwork.matchPacks(packs, rules);
        if (!matches.equals(java.util.List.of(packs.get(0), packs.get(2), packs.get(5), packs.get(6)))
                || !ClientReportNetwork.matchPacks(packs, " ,_,--,").isEmpty()
                || !ClientReportNetwork.matchPacks(java.util.List.of(), rules).isEmpty()) {
            throw new AssertionError("Resource pack indicators did not match the expected names: " + matches);
        }
        var report = ClientReportPayloads.collect(42, java.util.List.of(new ClientReportPayloads.Mod("create", "6.0", "Create")), packs);
        var snapshot = new ClientReportViewPayloads.View(11, "", "AuditTarget", 123, java.util.List.of(), matches, report);
        var commands = new ClientReportViewPayloads.View(12, "", "AuditTarget", 0,
                java.util.List.of("10.11.2026 23:00 · /home"), java.util.List.of(), null);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), server.registryAccess());
        try {
            for (var view : java.util.List.of(snapshot, commands)) {
                buffer.clear();
                ClientReportViewPayloads.View.CODEC.encode(buffer, view);
                if (!view.equals(ClientReportViewPayloads.View.CODEC.decode(buffer)) || buffer.isReadable()) {
                    throw new AssertionError("Structured client report changed on the wire");
                }
            }
        } finally {
            buffer.release();
        }
    }

    private static void checkConsoleStatus(MinecraftServer server, ClientReportPayloads.Report report) {
        var replies = new ArrayList<String>();
        var console = server.createCommandSourceStack().withSource(new CommandSource() {
            @Override public void sendSystemMessage(Component message) { replies.add(message.getString()); }
            @Override public boolean acceptsSuccess() { return true; }
            @Override public boolean acceptsFailure() { return true; }
            @Override public boolean shouldInformAdmins() { return false; }
        });
        ClientReportNetwork.writeReport(console, "AuditFixture", Instant.EPOCH, report);
        if (!replies.equals(java.util.List.of("[AvilixLogger] Отчёт доступен. Полный список можно открыть в игре."))) {
            throw new AssertionError("Server console exposed client report data: " + replies);
        }
        System.out.println("CLIENT_REPORT_CONSOLE_OK: no report contents in console output");
    }

    private static void measure(String phase, int wireBytes, int modCount, Runnable task) {
        for (int i = 0; i < WARMUP; i++) task.run();
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        long startAllocated = bean.getCurrentThreadAllocatedBytes();
        long[] nanos = new long[SAMPLES];
        for (int i = 0; i < nanos.length; i++) {
            long start = System.nanoTime();
            task.run();
            nanos[i] = System.nanoTime() - start;
        }
        long allocated = bean.getCurrentThreadAllocatedBytes() - startAllocated;
        Arrays.sort(nanos);
        System.out.printf(Locale.ROOT,
                "CLIENT_REPORT_PROFILE phase=%s wire_bytes=%d mods=%d samples=%d p95_us=%.1f p99_us=%.1f alloc_bytes_per_report=%d%n",
                phase, wireBytes, modCount, SAMPLES, nanos[1899] / 1_000.0, nanos[1979] / 1_000.0, allocated / SAMPLES);
    }
}
