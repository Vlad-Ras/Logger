package com.roften.avilixlogger.core;

import com.roften.avilixlogger.net.ServerLogPayloads;
import com.roften.avilixlogger.net.ServerLogPayloads.FileInfo;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.regex.Pattern;

/** Exposes only standard Minecraft server diagnostics beneath the game directory. */
public final class ServerLogFiles {
    private static final String LATEST = "latest.log";
    private static final Pattern ARCHIVE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}-[0-9]+\\.log\\.gz");
    private static final Pattern CRASH = Pattern.compile("crash-[0-9]{4}-[0-9]{2}-[0-9]{2}_[0-9]{2}\\.[0-9]{2}\\.[0-9]{2}-server\\.txt");
    private static final LinkOption NO_LINKS = LinkOption.NOFOLLOW_LINKS;
    private static final Comparator<FileInfo> BY_TIME = Comparator.comparingLong(FileInfo::modified).thenComparing(FileInfo::name);

    private ServerLogFiles() {}

    public record Snapshot(Path path, long size, Object fileKey, FileTime created) {}

    public static boolean allowedName(String name) {
        return LATEST.equals(name) || ARCHIVE.matcher(name).matches() || CRASH.matcher(name).matches();
    }

    public static List<FileInfo> list(Path root) throws IOException {
        var files = new ArrayList<FileInfo>(65);
        Path logs = root.resolve("logs");
        if (Files.isDirectory(logs, NO_LINKS)) {
            FileInfo latest = info(logs.resolve(LATEST));
            if (latest != null) files.add(latest);
            files.addAll(recent(logs, ARCHIVE));
        }
        Path crashes = root.resolve("crash-reports");
        if (Files.isDirectory(crashes, NO_LINKS)) files.addAll(recent(crashes, CRASH));
        return List.copyOf(files);
    }

    private static List<FileInfo> recent(Path directory, Pattern names) throws IOException {
        var newest = new PriorityQueue<>(32, BY_TIME);
        try (var paths = Files.newDirectoryStream(directory, p -> names.matcher(p.getFileName().toString()).matches())) {
            for (Path path : paths) {
                FileInfo file = info(path);
                if (file == null) continue;
                if (newest.size() < 32 || BY_TIME.compare(file, newest.peek()) > 0) {
                    if (newest.size() == 32) newest.remove();
                    newest.add(file);
                }
            }
        }
        return newest.stream().sorted(BY_TIME.reversed()).toList();
    }

    private static FileInfo info(Path path) {
        try {
            var attrs = attributes(path);
            return new FileInfo(path.getFileName().toString(), attrs.size(), attrs.lastModifiedTime().toMillis());
        } catch (IOException ignored) {
            return null;
        }
    }

    public static Snapshot open(Path root, String name) throws IOException {
        if (!allowedName(name)) throw new IOException("Invalid log file name");
        Path path = root.resolve(CRASH.matcher(name).matches() ? "crash-reports" : "logs").resolve(name);
        var attrs = attributes(path);
        if (attrs.size() > ServerLogPayloads.MAX_FILE_BYTES) throw new IOException("Log file exceeds the download limit");
        return new Snapshot(path, attrs.size(), attrs.fileKey(), attrs.creationTime());
    }

    public static byte[] read(Snapshot file, long offset) throws IOException {
        if (offset < 0 || offset > file.size()) throw new IOException("Invalid log file offset");
        verify(file);
        var bytes = ByteBuffer.allocate((int) Math.min(ServerLogPayloads.CHUNK_BYTES, file.size() - offset));
        try (var channel = FileChannel.open(file.path(), StandardOpenOption.READ, NO_LINKS)) {
            channel.position(offset);
            while (bytes.hasRemaining()) {
                if (channel.read(bytes) < 0) throw new EOFException("Log file shrank");
            }
        }
        verify(file);
        return bytes.array();
    }

    private static void verify(Snapshot file) throws IOException {
        var attrs = attributes(file.path());
        if (attrs.size() < file.size() || !Objects.equals(attrs.fileKey(), file.fileKey())
                || !attrs.creationTime().equals(file.created())) throw new IOException("Log file was rotated");
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        if (!Files.isDirectory(path.getParent(), NO_LINKS)) throw new IOException("Log directory is unavailable");
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, NO_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Expected a regular log file");
        return attrs;
    }
}
