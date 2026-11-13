package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.ServerLogPayloads;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Comparator;

final class ServerLogFilesTest {
    private ServerLogFilesTest() {}

    static void run() throws IOException {
        Path root = Files.createTempDirectory("avilix-log-export-test-");
        try {
            Path logs = Files.createDirectory(root.resolve("logs"));
            Path reports = Files.createDirectory(root.resolve("crash-reports"));
            byte[] contents = new byte[ServerLogPayloads.CHUNK_BYTES + 13];
            new java.util.Random(8).nextBytes(contents);
            Path latest = Files.write(logs.resolve("latest.log"), contents);
            String archive = "2026-11-13-2.log.gz";
            String crash = "crash-2026-11-13_23.55.41-server.txt";
            Files.write(logs.resolve(archive), new byte[]{1, 2});
            Files.writeString(reports.resolve(crash), "fixture");
            Files.writeString(logs.resolve("debug.log"), "private");
            Files.createSymbolicLink(reports.resolve("crash-2026-11-14_00.00.00-server.txt"), logs.resolve("debug.log"));
            check(ServerLogFiles.list(root).stream().map(f -> f.name()).toList()
                    .equals(java.util.List.of("latest.log", archive, crash)), "wrong file list");
            rejects(() -> ServerLogFiles.open(root, "../latest.log"));
            rejects(() -> ServerLogFiles.open(root, "debug.log"));
            rejects(() -> ServerLogFiles.open(root, "crash-2026-11-14_00.00.00-server.txt"));
            var snapshot = ServerLogFiles.open(root, "latest.log");
            Files.write(latest, new byte[]{42}, StandardOpenOption.APPEND);
            int split = ServerLogPayloads.CHUNK_BYTES;
            check(Arrays.equals(ServerLogFiles.read(snapshot, 0), Arrays.copyOfRange(contents, 0, split))
                    && Arrays.equals(ServerLogFiles.read(snapshot, split), Arrays.copyOfRange(contents, split, contents.length)),
                    "chunk bytes changed when latest.log grew");
            Files.move(latest, logs.resolve("old.log"));
            Files.write(latest, contents);
            rejects(() -> ServerLogFiles.read(snapshot, split));
            Files.move(reports, root.resolve("real-reports"));
            Files.createSymbolicLink(reports, root.resolve("real-reports"));
            rejects(() -> ServerLogFiles.open(root, crash));
        } finally {
            try (var paths = Files.walk(root)) {
                for (var file : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("SERVER_LOG_FILES_OK: allowed names, file and directory links, exact chunk bytes, growing logs and rotation");
    }

    @FunctionalInterface private interface Read { Object run() throws IOException; }

    private static void rejects(Read read) throws IOException {
        try { read.run(); } catch (IOException expected) { return; }
        throw new AssertionError("invalid file request succeeded");
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
