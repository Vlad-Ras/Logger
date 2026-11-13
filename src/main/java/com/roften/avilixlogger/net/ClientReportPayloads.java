package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ClientReportPayloads {
    private static final int MAX_MODS = 512;
    private static final int MAX_PACKS = 128;
    // Leave headroom below the serverbound custom payload limit.
    private static final int MAX_WIRE_BYTES = 30 * 1024;
    private static final int HEADER_BYTES = Long.BYTES + 1 + 2 * 5;
    private static final int MOD_FIELD_LENGTH = 96;
    private static final int PACK_NAME_LENGTH = 128;
    private static final int HASH_BYTES = 32;
    private static final java.util.regex.Pattern SHA256 = java.util.regex.Pattern.compile("[a-f0-9]{64}");

    private ClientReportPayloads() {}

    public static Report collect(long id, List<Mod> mods, Iterable<String> packs) {
        return collect(id, mods, packs, Map.of());
    }

    public static Report collect(long id, List<Mod> mods, Iterable<String> packs, Map<String, String> packHashes) {
        List<Mod> includedMods = new ArrayList<>();
        List<String> includedPacks = new ArrayList<>();
        var includedHashes = new LinkedHashMap<String, String>();
        int remaining = MAX_WIRE_BYTES - HEADER_BYTES;
        boolean truncated = false;
        for (String pack : packs) {
            String name = cleanPackId(pack);
            String hash = packHashes.getOrDefault(pack, "");
            boolean hashed = SHA256.matcher(hash).matches();
            int size = fieldBytes(name) + 1 + (hashed ? HASH_BYTES : 0);
            if (includedPacks.size() == MAX_PACKS || size > remaining) {
                truncated = true;
                break;
            }
            includedPacks.add(name);
            if (hashed) includedHashes.put(name, hash);
            remaining -= size;
        }
        for (Mod mod : mods) {
            int size = fieldBytes(mod.id()) + fieldBytes(mod.version()) + fieldBytes(mod.nameOnWire()) + 1 + (mod.sha256().isEmpty() ? 0 : HASH_BYTES);
            if (includedMods.size() == MAX_MODS || size > remaining) {
                truncated = true;
                break;
            }
            includedMods.add(mod);
            remaining -= size;
        }
        return new Report(id, includedMods, includedPacks, truncated, includedHashes);
    }

    private static int fieldBytes(String value) {
        int bytes = ByteBufUtil.utf8Bytes(value);
        return bytes + (bytes < 128 ? 1 : 2);
    }

    public record Request(long id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, request) -> buf.writeLong(request.id()), buf -> new Request(buf.readLong()));

        @Override
        public Type<Request> type() { return TYPE; }
    }

    public record Mod(String id, String version, String displayName, String sha256) {
        public Mod {
            id = clean(id, MOD_FIELD_LENGTH);
            version = clean(version, MOD_FIELD_LENGTH);
            displayName = displayName == null ? "" : clean(displayName, MOD_FIELD_LENGTH).strip();
            if (displayName.isEmpty()) displayName = id;
            if (sha256 == null || !SHA256.matcher(sha256).matches()) sha256 = "";
        }

        public Mod(String id, String version) {
            this(id, version, "", "");
        }

        public Mod(String id, String version, String displayName) {
            this(id, version, displayName, "");
        }

        public String label() {
            return displayName.equals(id) ? id : displayName + " (" + id + ")";
        }

        private String nameOnWire() {
            return displayName.equals(id) ? "" : displayName;
        }
    }

    public record Report(long id, List<Mod> mods, List<String> packs, boolean truncated,
                         Map<String, String> packHashes) implements CustomPacketPayload {
        public static final Type<Report> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_v4"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Report> CODEC = StreamCodec.of(
                Report::encode, Report::decode);

        public Report(long id, List<Mod> mods, List<String> packs, boolean truncated) {
            this(id, mods, packs, truncated, Map.of());
        }

        public Report {
            checkCount(mods.size(), MAX_MODS);
            checkCount(packs.size(), MAX_PACKS);
            mods = List.copyOf(mods);
            packs = packs.stream().map(ClientReportPayloads::cleanPackId).toList();
            if (!packHashes.isEmpty()) {
                var hashes = new LinkedHashMap<String, String>();
                for (String pack : packs) {
                    String hash = packHashes.get(pack);
                    if (hash != null && SHA256.matcher(hash).matches()) hashes.put(pack, hash);
                }
                packHashes = Map.copyOf(hashes);
            } else packHashes = Map.of();
        }

        public Report withTruncation(boolean wasTruncated) {
            return wasTruncated && !truncated ? new Report(id, mods, packs, true, packHashes) : this;
        }

        @Override
        public Type<Report> type() { return TYPE; }

        private static void encode(RegistryFriendlyByteBuf buf, Report report) {
            buf.writeLong(report.id());
            buf.writeBoolean(report.truncated());
            buf.writeVarInt(report.mods().size());
            for (Mod mod : report.mods()) {
                buf.writeUtf(mod.id(), MOD_FIELD_LENGTH);
                buf.writeUtf(mod.version(), MOD_FIELD_LENGTH);
                buf.writeUtf(mod.nameOnWire(), MOD_FIELD_LENGTH);
                writeHash(buf, mod.sha256());
            }
            buf.writeVarInt(report.packs().size());
            for (String pack : report.packs()) {
                buf.writeUtf(pack, PACK_NAME_LENGTH);
                writeHash(buf, report.packHashes().getOrDefault(pack, ""));
            }
        }

        private static Report decode(RegistryFriendlyByteBuf buf) {
            if (buf.readableBytes() > MAX_WIRE_BYTES) {
                throw new IllegalArgumentException("Client report exceeds the wire limit");
            }
            long id = buf.readLong();
            boolean truncated = buf.readBoolean();
            int modCount = checkCount(buf.readVarInt(), MAX_MODS);
            List<Mod> mods = new ArrayList<>(modCount);
            for (int i = 0; i < modCount; i++) {
                String modId = buf.readUtf(MOD_FIELD_LENGTH);
                String version = buf.readUtf(MOD_FIELD_LENGTH);
                String name = buf.readUtf(MOD_FIELD_LENGTH);
                mods.add(new Mod(modId, version, name, readHash(buf)));
            }
            int packCount = checkCount(buf.readVarInt(), MAX_PACKS);
            List<String> packs = new ArrayList<>(packCount);
            var hashes = new LinkedHashMap<String, String>();
            for (int i = 0; i < packCount; i++) {
                String pack = cleanPackId(buf.readUtf(PACK_NAME_LENGTH));
                packs.add(pack);
                String hash = readHash(buf);
                if (!hash.isEmpty()) hashes.put(pack, hash);
            }
            return new Report(id, mods, packs, truncated, hashes);
        }
    }

    private static void writeHash(RegistryFriendlyByteBuf buf, String hash) {
        buf.writeBoolean(!hash.isEmpty());
        if (!hash.isEmpty()) buf.writeBytes(java.util.HexFormat.of().parseHex(hash));
    }

    private static String readHash(RegistryFriendlyByteBuf buf) {
        if (!buf.readBoolean()) return "";
        byte[] bytes = new byte[HASH_BYTES];
        buf.readBytes(bytes);
        return java.util.HexFormat.of().formatHex(bytes);
    }

    private static int checkCount(int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid client report entry count: " + count);
        }
        return count;
    }

    private static String cleanPackId(String id) {
        int lastSlash = Math.max(id.lastIndexOf('/'), id.lastIndexOf('\\'));
        if (lastSlash > 4 && id.startsWith("file/")) id = "file/" + id.substring(lastSlash + 1);
        return clean(id, PACK_NAME_LENGTH);
    }

    private static String clean(String value, int maxLength) {
        StringBuilder cleaned = null;
        int offset = 0;
        while (offset < value.length()) {
            int codePoint = value.codePointAt(offset);
            int next = offset + Character.charCount(codePoint);
            int category = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR || category == Character.PARAGRAPH_SEPARATOR
                    || category == Character.SURROGATE || codePoint == '§') {
                if (cleaned == null) cleaned = new StringBuilder(Math.min(value.length(), maxLength)).append(value, 0, offset);
            } else if (cleaned == null) {
                if (next > maxLength) return value.substring(0, offset);
            } else {
                if (cleaned.length() + Character.charCount(codePoint) > maxLength) break;
                cleaned.appendCodePoint(codePoint);
            }
            offset = next;
        }
        return cleaned == null ? value : cleaned.toString();
    }
}
