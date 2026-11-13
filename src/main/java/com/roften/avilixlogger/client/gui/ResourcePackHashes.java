package com.roften.avilixlogger.client.gui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** Only selected local packs are read. No path or file contents leave the client. */
final class ResourcePackHashes {
    private static final int MAX_DIRECTORY_ENTRIES = 32_768;
    private static final int MAX_PACKS = 128;
    private static final long MAX_PACK_BYTES = 2L * 1024 * 1024 * 1024;
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;

    private ResourcePackHashes() {}

    static Map<String, String> collect(Path root, List<String> packs) {
        var result = new HashMap<String, String>();
        byte[] buffer = new byte[32 * 1024];
        for (int index = 0; index < Math.min(packs.size(), MAX_PACKS); index++) {
            String id = packs.get(index);
            if (!id.startsWith("file/")) continue;
            String name = id.substring(5);
            if (name.isEmpty() || name.equals(".") || name.equals("..")
                    || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) continue;
            try {
                Path relative = Path.of(name);
                if (relative.isAbsolute() || relative.getNameCount() != 1) continue;
                Path pack = root.resolve(relative);
                String hash = Files.isDirectory(pack, NO_LINKS) ? hashDirectory(pack, buffer) : hashFile(pack, buffer, MAX_PACK_BYTES);
                if (!hash.isEmpty()) result.put(id, hash);
            } catch (InvalidPathException | SecurityException ignored) {}
        }
        return result;
    }

    static String hashFile(Path file) {
        return hashFile(file, new byte[32 * 1024], Long.MAX_VALUE);
    }

    private static String hashFile(Path file, byte[] buffer, long limit) {
        try {
            if (!Files.isRegularFile(file, NO_LINKS) || Files.size(file) > limit) return "";
            var digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file, NO_LINKS)) {
                update(digest, input, buffer, limit);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException | SecurityException ignored) {
            return "";
        }
    }

    private static String hashDirectory(Path directory, byte[] buffer) {
        try (var paths = Files.walk(directory, 64)) {
            var entries = paths.skip(1).limit(MAX_DIRECTORY_ENTRIES + 1L).toList();
            if (entries.size() > MAX_DIRECTORY_ENTRIES) return "";
            for (Path path : entries) {
                if (Files.isSymbolicLink(path) || directory.relativize(path).getNameCount() >= 64) return "";
            }
            var files = entries.stream().filter(path -> Files.isRegularFile(path, NO_LINKS))
                    .sorted(Comparator.comparing(path -> relativeName(directory, path))).toList();
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update("avilixlogger-pack-tree-v1".getBytes(StandardCharsets.UTF_8));
            var framing = ByteBuffer.allocate(Integer.BYTES + Long.BYTES);
            long remaining = MAX_PACK_BYTES;
            for (Path file : files) {
                byte[] name = relativeName(directory, file).getBytes(StandardCharsets.UTF_8);
                long size = Files.size(file);
                if (size > remaining) return "";
                framing.clear();
                framing.putInt(name.length).putLong(size);
                digest.update(framing.array());
                digest.update(name);
                try (var input = Files.newInputStream(file, NO_LINKS)) {
                    if (update(digest, input, buffer, size) != size) return "";
                }
                remaining -= size;
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | java.io.UncheckedIOException | NoSuchAlgorithmException | SecurityException ignored) {
            return "";
        }
    }

    private static String relativeName(Path directory, Path file) {
        String name = directory.relativize(file).toString();
        return "\\".equals(directory.getFileSystem().getSeparator()) ? name.replace('\\', '/') : name;
    }

    private static long update(MessageDigest digest, InputStream input, byte[] buffer, long limit) throws IOException {
        long total = 0;
        for (int read; (read = input.read(buffer)) != -1;) {
            total += read;
            if (total > limit) throw new IOException("File exceeds hash limit");
            digest.update(buffer, 0, read);
        }
        return total;
    }
}
