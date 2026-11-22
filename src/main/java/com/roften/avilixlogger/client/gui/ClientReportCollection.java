package com.roften.avilixlogger.client.gui;

import com.roften.avilixlogger.net.ClientReportPayloads;
import com.roften.avilixlogger.net.ClientReportHashesPayload;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** Detached collection order is tested without a disk, a render loop or a live connection. */
final class ClientReportCollection {
    private ClientReportCollection() {}
    static CompletableFuture<Void> start(ClientReportPayloads.Report base, boolean incremental,
            Supplier<CompletableFuture<List<ClientReportPayloads.Mod>>> inventory,
            Supplier<Map<String, String>> packHashes, Executor worker,
            Consumer<ClientReportPayloads.Report> reports, Consumer<ClientReportHashesPayload> hashes) {
        // No archive I/O, executor admission or future completion may precede this delivery.
        if (incremental) reports.accept(base);
        return inventory.get().thenApplyAsync(mods -> {
            var packs = packHashes.get();
            if (incremental) ClientReportHashesPayload.split(base, mods, packs).forEach(hashes);
            else reports.accept(ClientReportPayloads.collect(base.id(), mods, base.packs(), packs).withTruncation(base.truncated()));
            return (Void) null;
        }, worker);
    }
}
