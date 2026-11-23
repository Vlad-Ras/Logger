package com.roften.avilixlogger.client.gui;

import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class ResourcePackHashesTest {
    public static void run() throws Exception {
        Path root = Files.createTempDirectory("logger-hash-filesystems-");
        Path archive = root.resolve("outer.jar"), plain = root.resolve("plain.jar"), link = root.resolve("link.jar");
        byte[] bytes = "nested mod archive fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Path closed;
        try {
            Files.write(plain, bytes);
            if (!ResourcePackHashes.hashFile(plain).equals(expected)) throw new AssertionError("local JAR hash changed");
            try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
                Path nested = zip.getPath("/inner.jar"); Files.write(nested, bytes); closed = nested;
                if (!ResourcePackHashes.hashFile(nested).equals(expected)) throw new AssertionError("nested JAR provider failed");
            }
            if (!ResourcePackHashes.hashFile(closed).isEmpty()) throw new AssertionError("closed provider reported a hash");
            if (!ResourcePackHashes.hashFile(plain).equals(expected)) throw new AssertionError("unavailable file poisoned next hash");
            try {
                Files.createSymbolicLink(link, plain);
                if (!ResourcePackHashes.hashFile(link).isEmpty()) throw new AssertionError("local symlink was followed");
            } catch (UnsupportedOperationException unsupported) { /* symlinks are optional on the host */ }
            var packs = ResourcePackHashes.collect(root, List.of("file/plain.jar", "file/missing.zip", "file/../plain.jar"));
            if (!packs.equals(Map.of("file/plain.jar", expected))) throw new AssertionError("pack path validation/hash changed");
        } finally { Files.deleteIfExists(link); Files.deleteIfExists(plain); Files.deleteIfExists(archive); Files.deleteIfExists(root); }
        System.out.println("CLIENT_REPORT_HASH_FILESYSTEMS_OK: exact local/nested JAR SHA-256, closed provider isolation, local symlink and path rejection");
    }
}
