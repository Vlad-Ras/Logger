package com.roften.avilixlogger.net;

import io.netty.buffer.ByteBufUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Optional hash supplements never delay or replace the already delivered inventory. */
public record ClientReportHashesPayload(long id, int part, int parts, boolean failed,
                                        Map<String, String> mods, Map<String, String> packs) implements CustomPacketPayload {
    public static final Type<ClientReportHashesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath("avilixlogger", "client_report_hashes_v1"));
    private static final int MAX_BYTES = 30 * 1024, MAX_PARTS = 8;
    private static final java.util.regex.Pattern HASH = java.util.regex.Pattern.compile("[a-f0-9]{64}");
    public static final StreamCodec<RegistryFriendlyByteBuf, ClientReportHashesPayload> CODEC = StreamCodec.of(
            (buf, value) -> {
                int start = buf.writerIndex();
                buf.writeLong(value.id()); buf.writeVarInt(value.part()); buf.writeVarInt(value.parts()); buf.writeBoolean(value.failed());
                write(buf, value.mods(), 96); write(buf, value.packs(), 128);
                if (buf.writerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Hash supplement exceeds wire budget");
            }, buf -> {
                int start = buf.readerIndex();
                var value = new ClientReportHashesPayload(buf.readLong(), buf.readVarInt(), buf.readVarInt(), buf.readBoolean(),
                        read(buf, 512, 96), read(buf, 128, 128));
                if (buf.readerIndex() - start > MAX_BYTES) throw new IllegalArgumentException("Hash supplement exceeds wire budget");
                return value;
            });

    public ClientReportHashesPayload {
        if (parts < 1 || parts > MAX_PARTS || part < 0 || part >= parts) throw new IllegalArgumentException("Invalid hash part");
        mods = validate(mods, 512, 96); packs = validate(packs, 128, 128);
        if (failed && (parts != 1 || !mods.isEmpty() || !packs.isEmpty())) throw new IllegalArgumentException("Invalid failed supplement");
        int size = 32;
        for (var entry : mods.entrySet()) size += size(entry.getKey());
        for (var entry : packs.entrySet()) size += size(entry.getKey());
        if (size > MAX_BYTES) throw new IllegalArgumentException("Hash supplement exceeds wire budget");
    }

    private static Map<String, String> validate(Map<String, String> map, int maximum, int length) {
        if (map.size() > maximum) throw new IllegalArgumentException("Too many hashes");
        for (var entry : map.entrySet()) {
            if (entry.getKey().isEmpty() || entry.getKey().length() > length || !HASH.matcher(entry.getValue()).matches())
                throw new IllegalArgumentException("Invalid hash entry");
        }
        return Map.copyOf(map);
    }
    private static int size(String key) { return 5 + ByteBufUtil.utf8Bytes(key) + 32; }
    private static void write(RegistryFriendlyByteBuf buf, Map<String, String> values, int length) {
        buf.writeVarInt(values.size());
        values.forEach((key, hash) -> { buf.writeUtf(key, length); buf.writeBytes(HexFormat.of().parseHex(hash)); });
    }
    private static Map<String, String> read(RegistryFriendlyByteBuf buf, int maximum, int length) {
        int count = buf.readVarInt();
        if (count < 0 || count > maximum) throw new IllegalArgumentException("Too many hashes");
        var result = new LinkedHashMap<String, String>();
        for (int i = 0; i < count; i++) {
            String key = buf.readUtf(length); byte[] hash = new byte[32]; buf.readBytes(hash);
            if (result.putIfAbsent(key, HexFormat.of().formatHex(hash)) != null) throw new IllegalArgumentException("Duplicate hash key");
        }
        return result;
    }
    @Override public Type<ClientReportHashesPayload> type() { return TYPE; }

    public static List<ClientReportHashesPayload> split(ClientReportPayloads.Report base,
                                                         List<ClientReportPayloads.Mod> hashedMods, Map<String, String> hashedPacks) {
        var allowedMods = new HashSet<String>(); base.mods().forEach(mod -> allowedMods.add(mod.id()));
        var allowedPacks = new HashSet<>(base.packs());
        var chunks = new ArrayList<ClientReportHashesPayload>();
        var mods = new LinkedHashMap<String, String>(); var packs = new LinkedHashMap<String, String>();
        int size = 32;
        for (var mod : hashedMods) {
            if (!allowedMods.contains(mod.id()) || mod.sha256().isEmpty()) continue;
            if (size + size(mod.id()) > MAX_BYTES) {
                chunks.add(new ClientReportHashesPayload(base.id(), 0, 1, false, mods, packs));
                mods = new LinkedHashMap<>(); packs = new LinkedHashMap<>(); size = 32;
            }
            mods.put(mod.id(), mod.sha256()); size += size(mod.id());
        }
        var sentPacks = new HashSet<String>();
        for (var entry : hashedPacks.entrySet()) {
            String key = ClientReportPayloads.cleanPackId(entry.getKey());
            if (!allowedPacks.contains(key) || !HASH.matcher(entry.getValue()).matches() || !sentPacks.add(key)) continue;
            if (size + size(key) > MAX_BYTES) {
                chunks.add(new ClientReportHashesPayload(base.id(), 0, 1, false, mods, packs));
                mods = new LinkedHashMap<>(); packs = new LinkedHashMap<>(); size = 32;
            }
            packs.put(key, entry.getValue()); size += size(key);
        }
        chunks.add(new ClientReportHashesPayload(base.id(), 0, 1, false, mods, packs));
        if (chunks.size() > MAX_PARTS) throw new IllegalArgumentException("Too many hash parts");
        var result = new ArrayList<ClientReportHashesPayload>();
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            result.add(new ClientReportHashesPayload(base.id(), i, chunks.size(), false, chunk.mods(), chunk.packs()));
        }
        return List.copyOf(result);
    }

    /** Server-thread owned, at most one bounded assembly for the current request/connection. */
    public static final class Assembly {
        private final ClientReportPayloads.Report base;
        private final Set<String> allowedMods = new HashSet<>(), allowedPacks;
        private final Map<String, String> mods = new HashMap<>(), packs = new HashMap<>();
        private int next, parts;
        private boolean failed;
        public Assembly(ClientReportPayloads.Report base) {
            this.base = base; base.mods().forEach(mod -> allowedMods.add(mod.id())); allowedPacks = new HashSet<>(base.packs());
        }
        public boolean accept(ClientReportHashesPayload value) {
            if (value.id() != base.id() || complete() || value.part() != next || (next > 0 && value.parts() != parts))
                throw new IllegalArgumentException("Stale or unordered hash supplement");
            if (!allowedMods.containsAll(value.mods().keySet()) || !allowedPacks.containsAll(value.packs().keySet())
                    || !Collections.disjoint(mods.keySet(), value.mods().keySet()) || !Collections.disjoint(packs.keySet(), value.packs().keySet()))
                throw new IllegalArgumentException("Hash supplement differs from inventory");
            parts = value.parts(); next++; failed = value.failed(); mods.putAll(value.mods()); packs.putAll(value.packs());
            return complete();
        }
        public boolean complete() { return parts > 0 && next == parts; }
        public boolean failed() { return failed; }
        public ClientReportPayloads.Report report() {
            if (!complete()) throw new IllegalStateException("Hash supplement incomplete");
            var result = base.mods().stream().map(mod -> new ClientReportPayloads.Mod(mod.id(), mod.version(), mod.displayName(),
                    mods.getOrDefault(mod.id(), mod.sha256()))).toList();
            var hashes = new HashMap<>(base.packHashes()); hashes.putAll(packs);
            return new ClientReportPayloads.Report(base.id(), result, base.packs(), base.truncated(), hashes);
        }
    }
}
