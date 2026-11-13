package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.create.CreatePlayerProgress;
import com.roften.avilixlogger.compat.create.mixin.PlayerStatsAccessor;
import com.sun.management.ThreadMXBean;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stat;
import net.minecraft.stats.Stats;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.function.Supplier;

/** Matched A/B scans of populated native player statistics in the real dedicated-server JVM. */
final class PlayerProgressCostSmoke {
    private static final int ITERATIONS = 800;
    private static volatile CompoundTag consumed;

    private PlayerProgressCostSmoke() {}

    static void start(ServerPlayer player) {
        var entries = ((PlayerStatsAccessor) player.getStats()).avilixlogger$stats();
        int count = 0;
        for (var item : BuiltInRegistries.ITEM) {
            entries.put(Stats.ITEM_USED.get(item), ++count);
            if (count == 512) break;
        }
        var initial = CreatePlayerProgress.capture(player);
        initial.remove("Stats");
        Supplier<CompoundTag> baseline = () -> oldCapture(player, initial);
        Supplier<CompoundTag> candidate = () -> CreatePlayerProgress.capture(player);
        if (!baseline.get().equals(candidate.get())) throw new AssertionError("Create progress snapshot changed");
        var counter = Stats.ITEM_USED.get(net.minecraft.world.item.Items.APPLE);
        boolean recorded = entries.containsKey(counter);
        int previous = entries.getInt(counter);
        entries.put(counter, previous + 1000);
        var detached = candidate.get();
        if (!detached.equals(baseline.get())) throw new AssertionError("Changed native counter missing");
        if (recorded) entries.put(counter, previous);
        else entries.removeInt(counter);
        if (detached.equals(candidate.get())) throw new AssertionError("Detached progress changed with live counters");

        for (int i = 0; i < ITERATIONS; i++) {
            consumed = baseline.get();
            consumed = candidate.get();
        }
        var bean = (ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (!bean.isThreadAllocatedMemoryEnabled()) bean.setThreadAllocatedMemoryEnabled(true);
        var baselineBytes = new long[5]; var candidateBytes = new long[5];
        var baselineCpu = new long[5]; var candidateCpu = new long[5];
        for (int round = 0; round < 5; round++) {
            var first = round % 2 == 0 ? measure(bean, baseline) : measure(bean, candidate);
            var second = round % 2 == 0 ? measure(bean, candidate) : measure(bean, baseline);
            var old = round % 2 == 0 ? first : second;
            var next = round % 2 == 0 ? second : first;
            baselineBytes[round] = old.bytes(); candidateBytes[round] = next.bytes();
            baselineCpu[round] = old.cpu(); candidateCpu[round] = next.cpu();
        }
        System.out.printf(Locale.ROOT,
                "CREATE_PROGRESS_PROFILE counters=%d iterations=%d rounds=5 old_bytes=%d new_bytes=%d old_cpu_ns=%d new_cpu_ns=%d%n",
                entries.size(), ITERATIONS, median(baselineBytes), median(candidateBytes), median(baselineCpu), median(candidateCpu));
        var sampler = new Ticks(baseline, candidate);
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST, sampler::pre);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, sampler::post);
    }

    private static final class Ticks {
        private static final int WARMUP_TICKS = 20;
        private static final int SAMPLES = 60;
        private static final int CAPTURES_PER_TICK = 16;

        private final Supplier<CompoundTag> baseline;
        private final Supplier<CompoundTag> candidate;
        private final long[] durations = new long[SAMPLES];
        private int phase;
        private int tick;
        private long startedAt;
        private long firstMeasuredAt;

        Ticks(Supplier<CompoundTag> baseline, Supplier<CompoundTag> candidate) {
            this.baseline = baseline;
            this.candidate = candidate;
        }

        private void pre(ServerTickEvent.Pre event) {
            if (phase >= 3) return;
            startedAt = System.nanoTime();
            if (tick == WARMUP_TICKS) firstMeasuredAt = startedAt;
            if (phase > 0) {
                var capture = phase == 1 ? baseline : candidate;
                for (int i = 0; i < CAPTURES_PER_TICK; i++) consumed = capture.get();
            }
        }

        private void post(ServerTickEvent.Post event) {
            if (phase >= 3) return;
            long elapsed = System.nanoTime() - startedAt;
            int sample = tick++ - WARMUP_TICKS;
            if (sample < 0) return;
            durations[sample] = elapsed;
            if (sample + 1 != SAMPLES) return;
            var name = switch (phase) {
                case 0 -> "idle";
                case 1 -> "baseline";
                default -> "candidate";
            };
            Arrays.sort(durations);
            var heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
            System.out.printf(Locale.ROOT,
                    "LOGGER_TICK_PROFILE phase=%s ticks=%d captures_per_tick=%d tps=%.2f p50_mspt=%.3f p95_mspt=%.3f heap_used_mib=%.1f%n",
                    name, SAMPLES, phase == 0 ? 0 : CAPTURES_PER_TICK,
                    (SAMPLES - 1) * 1e9 / (startedAt - firstMeasuredAt), durations[SAMPLES / 2] / 1e6,
                    durations[SAMPLES * 95 / 100 - 1] / 1e6, heap.getUsed() / 1048576.0);
            tick = 0;
            if (++phase == 3) {
                consumed = null;
                event.getServer().halt(false);
            }
        }
    }

    private record Sample(long bytes, long cpu) {}

    private static Sample measure(ThreadMXBean bean, Supplier<CompoundTag> capture) {
        long allocated = bean.getCurrentThreadAllocatedBytes();
        long cpu = bean.getCurrentThreadCpuTime();
        for (int i = 0; i < ITERATIONS; i++) consumed = capture.get();
        return new Sample((bean.getCurrentThreadAllocatedBytes() - allocated) / ITERATIONS,
                (bean.getCurrentThreadCpuTime() - cpu) / ITERATIONS);
    }

    private static long median(long[] values) {
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static CompoundTag oldCapture(ServerPlayer player, CompoundTag otherFields) {
        var stats = new ListTag();
        var entries = ((PlayerStatsAccessor) player.getStats()).avilixlogger$stats().object2IntEntrySet().stream()
                .sorted(Comparator.comparing(e -> e.getKey().toString())).toList();
        for (var entry : entries) {
            var row = new CompoundTag();
            var stat = entry.getKey();
            row.putString("Type", BuiltInRegistries.STAT_TYPE.getKey(stat.getType()).toString());
            row.putString("Value", value(stat));
            row.putInt("Count", entry.getIntValue());
            stats.add(row);
        }
        var out = otherFields.copy();
        out.put("Stats", stats);
        CreatePlayerProgress.validate(out);
        return out;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String value(Stat<?> stat) {
        return ((net.minecraft.core.Registry) stat.getType().getRegistry()).getKey(stat.getValue()).toString();
    }
}
