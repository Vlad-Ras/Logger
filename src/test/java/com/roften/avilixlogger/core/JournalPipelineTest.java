package com.roften.avilixlogger.core;

import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

final class JournalPipelineTest {
    static void run() throws Exception {
        Path directory = Files.createTempDirectory("avilixlogger-journal-test");
        try {
            PendingLogStorage offline = new PendingLogStorage(directory, 256, 1024 * 1024, 100, 10);
            for (int i = 0; i < 1000; i++) {
                LogEntry row = row("строка " + i); row.count = i;
                row.beBefore = "{Items:[{id:'minecraft:diamond',count:2}]}";
                offline.append(row);
            }
            offline.shutdown();
            check(offline.retainedBytes() == 0, "shutdown retained journal heap");
            DiskLogJournal disk = new DiskLogJournal(directory);
            List<Path> files = disk.pending();
            List<LogEntry> saved = new ArrayList<>();
            for (Path file : files) saved.addAll(DiskLogJournal.read(file));
            check(saved.size() == 1000, "offline journal lost rows");
            check(saved.get(600).extra.equals("строка 600") && saved.get(600).count == 600, "journal changed row contents/order");

            // A completed .tmp survives interrupted publication and is recovered on restart.
            Path first = files.getFirst();
            Files.move(first, first.resolveSibling(first.getFileName().toString().replace(".jsonl", ".tmp")));
            FakeDatabase database = new FakeDatabase();
            PendingLogStorage restarted = new PendingLogStorage(directory, 256, 1024 * 1024, 100, 10);
            restarted.setDelegate(database);
            await(() -> database.rows.get() == 1000, "restart did not replay every durable row");
            await(() -> {
                try { return disk.pending().isEmpty(); } catch (IOException error) { return false; }
            }, "acknowledged batches were not removed");
            restarted.shutdown();
            check(database.attempts.get() > files.size(), "failure/retry path was not exercised");
            check(database.originalCounts.get() == 499500, "retry changed item counts");

            Path broken = directory.resolve("broken.jsonl");
            Files.writeString(broken, "avilixlogger-journal-v1\n2\n{}\n");
            try { DiskLogJournal.read(broken); throw new AssertionError("partial journal accepted"); }
            catch (IOException expected) {}
            check(Files.exists(broken), "malformed journal was deleted");
        } finally {
            try (var walk = Files.walk(directory)) {
                for (Path file : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        }
    }

    private static final class FakeDatabase implements BatchLogStorage {
        final Set<String> accepted = new HashSet<>();
        final Map<String, String> contents = new HashMap<>();
        final AtomicInteger rows = new AtomicInteger(), attempts = new AtomicInteger(), originalCounts = new AtomicInteger();
        @Override public synchronized void appendBatch(String token, List<LogEntry> entries) throws IOException {
            String serialized = GzipJson.GSON.toJson(entries);
            String previous = contents.putIfAbsent(token, serialized);
            check(previous == null || previous.equals(serialized), "retry mutated a durable batch");
            if (accepted.add(token)) {
                rows.addAndGet(entries.size());
                for (LogEntry row : entries) originalCounts.addAndGet(row.count);
            }
            if (attempts.getAndIncrement() == 0) throw new IOException("simulated lost acknowledgment");
        }
        public void append(LogEntry row) { throw new AssertionError("delivery bypassed batch acknowledgment"); }
        public List<LogEntry> query(LogQuery query) { return List.of(); }
        public List<LogEntry> queryReverse(LogQuery query) { return List.of(); }
        public void shutdown() {}
    }
}
