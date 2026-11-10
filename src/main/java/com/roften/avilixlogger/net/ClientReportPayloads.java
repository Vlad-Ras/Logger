package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class ClientReportPayloads {
    private static final int MAX_MODS = 512;
    private static final int MAX_PACKS = 128;
    // Leave headroom below the serverbound custom payload limit.
    private static final int MAX_WIRE_BYTES = 30 * 1024;
    private static final int HEADER_BYTES = Long.BYTES + 1 + 2 * 5;
    private static final int MOD_FIELD_LENGTH = 96;
    private static final int PACK_NAME_LENGTH = 128;

    private ClientReportPayloads() {}

    public static Report collect(long id, List<Mod> mods, Iterable<String> packs) {
        List<Mod> includedMods = new ArrayList<>();
        List<String> includedPacks = new ArrayList<>();
        int remaining = MAX_WIRE_BYTES - HEADER_BYTES;
        boolean truncated = false;
        for (String pack : packs) {
            String name = cleanPackId(pack);
            int size = fieldBytes(name);
            if (includedPacks.size() == MAX_PACKS || size > remaining) {
                truncated = true;
                break;
            }
            includedPacks.add(name);
            remaining -= size;
        }
        for (Mod mod : mods) {
            int size = fieldBytes(mod.id()) + fieldBytes(mod.version());
            if (includedMods.size() == MAX_MODS || size > remaining) {
                truncated = true;
                break;
            }
            includedMods.add(mod);
            remaining -= size;
        }
        return new Report(id, includedMods, includedPacks, truncated);
    }

    private static int fieldBytes(String value) {
        return ByteBufUtil.utf8Bytes(value) + 5;
    }

    public record Request(long id) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
                (buf, request) -> buf.writeLong(request.id()), buf -> new Request(buf.readLong()));

        @Override
        public Type<Request> type() { return TYPE; }
    }

    public record Mod(String id, String version) {
        public Mod {
            id = clean(id, MOD_FIELD_LENGTH);
            version = clean(version, MOD_FIELD_LENGTH);
        }
    }

    public record Report(long id, List<Mod> mods, List<String> packs, boolean truncated) implements CustomPacketPayload {
        public static final Type<Report> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Report> CODEC = StreamCodec.of(
                Report::encode, Report::decode);

        public Report {
            checkCount(mods.size(), MAX_MODS);
            checkCount(packs.size(), MAX_PACKS);
            mods = List.copyOf(mods);
            packs = packs.stream().map(ClientReportPayloads::cleanPackId).toList();
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
            }
            buf.writeVarInt(report.packs().size());
            for (String pack : report.packs()) {
                buf.writeUtf(pack, PACK_NAME_LENGTH);
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
                mods.add(new Mod(buf.readUtf(MOD_FIELD_LENGTH), buf.readUtf(MOD_FIELD_LENGTH)));
            }
            int packCount = checkCount(buf.readVarInt(), MAX_PACKS);
            List<String> packs = new ArrayList<>(packCount);
            for (int i = 0; i < packCount; i++) {
                packs.add(buf.readUtf(PACK_NAME_LENGTH));
            }
            return new Report(id, mods, packs, truncated);
        }
    }

    private static int checkCount(int count, int max) {
        if (count < 0 || count > max) {
            throw new IllegalArgumentException("Invalid client report entry count: " + count);
        }
        return count;
    }

    private static String cleanPackId(String id) {
        int lastSlash = Math.max(id.lastIndexOf('/'), id.lastIndexOf('\\'));
        return clean(id.startsWith("file/") ? "file/" + id.substring(lastSlash + 1) : id, PACK_NAME_LENGTH);
    }

    private static String clean(String value, int maxLength) {
        StringBuilder result = new StringBuilder(Math.min(value.length(), maxLength));
        for (int offset = 0; offset < value.length() && result.length() < maxLength;) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int category = Character.getType(codePoint);
            if (Character.isISOControl(codePoint) || category == Character.FORMAT
                    || category == Character.LINE_SEPARATOR || category == Character.PARAGRAPH_SEPARATOR
                    || category == Character.SURROGATE || codePoint == '§') continue;
            if (result.length() + Character.charCount(codePoint) > maxLength) break;
            result.appendCodePoint(codePoint);
        }
        return result.toString();
    }
}
