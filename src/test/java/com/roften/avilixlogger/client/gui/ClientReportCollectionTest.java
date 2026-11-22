package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.*;
import java.util.*;
import java.util.concurrent.*;

public final class ClientReportCollectionTest {
    public static void run() {
        var mods = List.of(new ClientReportPayloads.Mod("fixture", "1", "Fixture"));
        var base = ClientReportPayloads.collect(41, mods, List.of("file/test.zip"));
        var pending = new CompletableFuture<List<ClientReportPayloads.Mod>>();
        var work = new ArrayDeque<Runnable>();
        var reports = new ArrayList<ClientReportPayloads.Report>();
        var hashes = new ArrayList<ClientReportHashesPayload>();
        var result = ClientReportCollection.start(base, true, () -> {
            check(reports.equals(List.of(base)), "metadata waited for hash task admission"); return pending;
        }, () -> Map.of("file/test.zip", "bc".repeat(32)), work::add, reports::add, hashes::add);
        check(reports.size() == 1 && !result.isDone() && work.isEmpty(), "slow disk blocked initial report");
        pending.complete(List.of(new ClientReportPayloads.Mod("fixture", "1", "Fixture", "ab".repeat(32))));
        check(reports.size() == 1 && hashes.isEmpty(), "hash completion ran on collection caller");
        work.remove().run(); result.join();
        var assembly = new ClientReportHashesPayload.Assembly(base);
        hashes.forEach(assembly::accept);
        check(assembly.report().mods().getFirst().sha256().equals("ab".repeat(32))
                && assembly.report().packHashes().size() == 1, "supplement lost hashes");

        reports.clear(); hashes.clear();
        var failed = ClientReportCollection.start(base, true,
                () -> CompletableFuture.completedFuture(mods), Map::of,
                task -> { throw new RejectedExecutionException("fixture"); }, reports::add, hashes::add);
        check(failed.isCompletedExceptionally() && reports.equals(List.of(base)), "busy hash worker lost metadata");

        reports.clear();
        var legacyPending = new CompletableFuture<List<ClientReportPayloads.Mod>>();
        var legacy = ClientReportCollection.start(base, false, () -> legacyPending, Map::of, work::add, reports::add, hashes::add);
        check(reports.isEmpty(), "legacy server received unsupported staged report");
        legacyPending.complete(mods); work.remove().run(); legacy.join();
        check(reports.size() == 1, "legacy fallback failed");

        var manyMods = new ArrayList<ClientReportPayloads.Mod>(); var manyPacks = new ArrayList<String>();
        var packHashes = new LinkedHashMap<String, String>();
        for (int i = 0; i < 512; i++) manyMods.add(new ClientReportPayloads.Mod("m" + i, "1", "", "ab".repeat(32)));
        for (int i = 0; i < 128; i++) { String pack = "file/" + i + "x".repeat(100); manyPacks.add(pack); packHashes.put(pack, "cd".repeat(32)); }
        var metadata = ClientReportPayloads.collect(71, manyMods.stream().map(m -> new ClientReportPayloads.Mod(m.id(),m.version())).toList(), manyPacks);
        check(metadata.mods().size() == 512 && metadata.packs().size() == 128 && !metadata.truncated(), "fixture did not fill inventory");
        var parts = ClientReportHashesPayload.split(metadata, manyMods, packHashes);
        check(parts.size() > 1, "fixture did not exercise multiple packets");
        var full = new ClientReportHashesPayload.Assembly(metadata); parts.forEach(full::accept);
        check(full.report().mods().size() == 512 && full.report().packHashes().size() == 128
                && full.report().mods().stream().allMatch(m -> !m.sha256().isEmpty()) && !full.report().truncated(),
                "hash wire budget reduced inventory coverage");
        reject(() -> full.accept(parts.getFirst()));
        reject(() -> new ClientReportHashesPayload.Assembly(metadata).accept(parts.getLast()));
        reject(() -> new ClientReportHashesPayload.Assembly(base).accept(new ClientReportHashesPayload(41,0,1,false,Map.of("other", "ab".repeat(32)),Map.of())));
        reject(() -> new ClientReportHashesPayload.Assembly(base).accept(new ClientReportHashesPayload(42,0,1,false,Map.of(),Map.of())));
        reject(() -> new ClientReportHashesPayload(41,0,9,false,Map.of(),Map.of()));
        reject(() -> new ClientReportHashesPayload(41,0,1,true,Map.of("fixture", "ab".repeat(32)),Map.of()));
        var failedHashes = new ClientReportHashesPayload.Assembly(base);
        failedHashes.accept(new ClientReportHashesPayload(41,0,1,true,Map.of(),Map.of()));
        check(failedHashes.failed() && failedHashes.report().equals(base), "failed hash step destroyed metadata");
        System.out.println("CLIENT_REPORT_FAST_PATH_OK: initial metadata precedes blocked/rejected hashing; legacy fallback; full 512/128 inventory and bounded supplements; stale/foreign parts rejected");
    }
    private static void reject(Runnable action) { try { action.run(); } catch (IllegalArgumentException expected) { return; } throw new AssertionError("invalid supplement accepted"); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
