package com.roften.avilixlogger.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public final class ServerLogPayloads {
    public static final int CHUNK_BYTES = 128 * 1024;
    public static final long MAX_FILE_BYTES = 128L * 1024 * 1024;
    static final int MAX_ENTRIES = 65;
    private static final int MAX_NAME = 96;

    private ServerLogPayloads() {}

    public enum Action { LIST, START, NEXT, CANCEL }
    public enum Result { LIST, DATA, ERROR }

    public record FileInfo(String name, long size, long modified) {}

    public record Query(long request, Action action, String name, long offset) implements CustomPacketPayload {
        public static final Type<Query> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("avilixlogger", "server_log_query"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Query> CODEC = StreamCodec.of(
                (buf, p) -> {
                    buf.writeLong(p.request());
                    buf.writeEnum(p.action());
                    if (p.action() == Action.START) buf.writeUtf(p.name(), MAX_NAME);
                    if (p.action() == Action.NEXT) buf.writeVarLong(p.offset());
                },
                buf -> {
                    long request = buf.readLong();
                    var action = buf.readEnum(Action.class);
                    String name = action == Action.START ? buf.readUtf(MAX_NAME) : "";
                    return new Query(request, action, name, action == Action.NEXT ? buf.readVarLong() : 0);
                });

        @Override public Type<Query> type() { return TYPE; }
    }

    public record View(long request, Result result, String name, long offset, long total,
                       byte[] data, List<FileInfo> files, String message) implements CustomPacketPayload {
        public static final Type<View> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath("avilixlogger", "server_log_result"));
        public static final StreamCodec<RegistryFriendlyByteBuf, View> CODEC = StreamCodec.of(View::encode, View::decode);

        static View list(long request, List<FileInfo> files) {
            return new View(request, Result.LIST, "", 0, 0, new byte[0], files, "");
        }

        static View data(long request, String name, long offset, long total, byte[] data) {
            return new View(request, Result.DATA, name, offset, total, data, List.of(), "");
        }

        static View error(long request, String message) {
            return new View(request, Result.ERROR, "", 0, 0, new byte[0], List.of(), message);
        }

        private static void encode(RegistryFriendlyByteBuf buf, View p) {
            buf.writeLong(p.request());
            buf.writeEnum(p.result());
            switch (p.result()) {
                case LIST -> {
                    buf.writeVarInt(p.files().size());
                    for (var file : p.files()) {
                        buf.writeUtf(file.name(), MAX_NAME);
                        buf.writeVarLong(file.size());
                        buf.writeLong(file.modified());
                    }
                }
                case DATA -> {
                    buf.writeUtf(p.name(), MAX_NAME);
                    buf.writeVarLong(p.offset());
                    buf.writeVarLong(p.total());
                    buf.writeByteArray(p.data());
                }
                case ERROR -> buf.writeUtf(p.message(), 256);
            }
        }

        private static View decode(RegistryFriendlyByteBuf buf) {
            long request = buf.readLong();
            return switch (buf.readEnum(Result.class)) {
                case LIST -> {
                    int count = buf.readVarInt();
                    if (count < 0 || count > MAX_ENTRIES) throw new IllegalArgumentException("Invalid log file count");
                    var files = new ArrayList<FileInfo>(count);
                    for (int i = 0; i < count; i++) files.add(new FileInfo(buf.readUtf(MAX_NAME), buf.readVarLong(), buf.readLong()));
                    yield list(request, List.copyOf(files));
                }
                case DATA -> data(request, buf.readUtf(MAX_NAME), buf.readVarLong(), buf.readVarLong(), buf.readByteArray(CHUNK_BYTES));
                case ERROR -> error(request, buf.readUtf(256));
            };
        }

        @Override public Type<View> type() { return TYPE; }
    }
}
