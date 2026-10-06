package com.roften.avilixlogger.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable, versioned batches. Only fully written files are visible to the sender. */
final class DiskLogJournal {
    private static final String HEADER = "avilixlogger-journal-v1";
    private final Path directory;
    private long sequence;

    DiskLogJournal(Path directory) { this.directory = directory; }

    void initialize() throws IOException {
        Files.createDirectories(directory);
        // A .tmp is a batch whose publication was interrupted. Never delete an incomplete one.
        try (var files = Files.newDirectoryStream(directory, "*.tmp")) {
            for (Path file : files) {
                try {
                    read(file);
                    publish(file, directory.resolve(file.getFileName().toString().replace(".tmp", ".jsonl")));
                } catch (IOException | RuntimeException incomplete) {
                    Path retained = file.resolveSibling(file.getFileName() + ".incomplete");
                    Files.move(file, retained);
                    com.roften.avilixlogger.AvilixLoggerMod.LOGGER.error(
                            "[AvilixLogger] Interrupted journal batch retained for recovery: {}", retained, incomplete);
                }
            }
        }
    }

    String newToken() {
        return String.format(java.util.Locale.ROOT, "%019d-%06d-%s", System.currentTimeMillis(), sequence++, UUID.randomUUID());
    }

    Path write(String name, List<LogEntry> rows) throws IOException {
        Path temporary = directory.resolve(name + ".tmp");
        Path ready = directory.resolve(name + ".jsonl");
        if (Files.exists(ready)) return ready;
        try (FileOutputStream stream = new FileOutputStream(temporary.toFile());
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(stream, StandardCharsets.UTF_8), 64 * 1024)) {
            writer.write(HEADER); writer.newLine();
            writer.write(Integer.toString(rows.size())); writer.newLine();
            for (LogEntry row : rows) {
                GzipJson.GSON.toJson(row, writer);
                writer.newLine();
            }
            writer.flush();
            stream.getChannel().force(true);
        } catch (IOException | RuntimeException error) {
            // This live process still owns all rows and retries the same batch in memory.
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) {}
            throw error;
        }
        publish(temporary, ready);
        return ready;
    }

    private static void publish(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(from, to); }
    }

    List<Path> pending() throws IOException {
        ArrayList<Path> paths = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.jsonl")) {
            for (Path file : files) paths.add(file);
        }
        paths.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return paths;
    }

    static List<LogEntry> read(Path path) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            if (!HEADER.equals(reader.readLine())) throw new IOException("Unknown journal version: " + path);
            int count = Integer.parseInt(reader.readLine());
            if (count < 1 || count > 1_000_000) throw new IOException("Invalid batch size: " + count);
            ArrayList<LogEntry> rows = new ArrayList<>(Math.min(count, 10_000));
            for (int i = 0; i < count; i++) {
                String line = reader.readLine();
                if (line == null) throw new EOFException("Incomplete journal batch: " + path);
                LogEntry row = GzipJson.GSON.fromJson(line, LogEntry.class);
                if (row == null || row.type == null || row.id <= 0) throw new IOException("Invalid journal row: " + path);
                rows.add(row);
            }
            if (reader.readLine() != null) throw new IOException("Unexpected trailing journal data: " + path);
            return rows;
        } catch (RuntimeException malformed) { throw new IOException("Malformed journal batch: " + path, malformed); }
    }

    static void acknowledge(Path path) throws IOException { Files.delete(path); }
}
