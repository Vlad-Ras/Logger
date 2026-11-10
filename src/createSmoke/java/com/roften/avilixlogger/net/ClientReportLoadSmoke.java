package com.roften.avilixlogger.net;

import com.sun.management.ThreadMXBean;
import io.netty.buffer.Unpooled;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.MinecraftServer;

import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/** Measures report work in isolation on the native server, outside the regular tick loop. */
public final class ClientReportLoadSmoke {
    private static final int WARMUP = 750;
    private static final int SAMPLES = 2_000;

    private ClientReportLoadSmoke() {}

    public static void run(MinecraftServer server) {
        var mods = IntStream.range(0, 512)
                .mapToObj(i -> new ClientReportPayloads.Mod("fixture_mod_" + i + "_" + "m".repeat(60), "1.2.3+fixture"))
                .toList();
        var packs = IntStream.range(0, 24).mapToObj(i -> "file/fixture-pack-" + i + ".zip").toList();
        var report = ClientReportPayloads.collect(1, mods, packs);
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
