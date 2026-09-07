package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Server -> Client: a rendered page for the GUI (+ row metadata for actions). */
public record S2CLogPagePayload(String title, int pageIndex, boolean hasPrev, boolean hasNext, List<LogRow> rows) implements CustomPacketPayload {

    public static final Type<S2CLogPagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_page_data"));


    public static final StreamCodec<RegistryFriendlyByteBuf, S2CLogPagePayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeUtf(msg.title == null ? "" : msg.title, 32767);
                buf.writeVarInt(msg.pageIndex);
                buf.writeBoolean(msg.hasPrev);
                buf.writeBoolean(msg.hasNext);
                List<LogRow> rows = msg.rows == null ? List.of() : msg.rows;
                buf.writeVarInt(rows.size());
                for (LogRow r : rows) {
                    buf.writeVarLong(r.id());
                    buf.writeUtf(r.dim() == null ? "" : r.dim(), 32767);
                    buf.writeVarInt(r.x());
                    buf.writeVarInt(r.y());
                    buf.writeVarInt(r.z());
                    // RegistryFriendlyByteBuf in NeoForge 21.1.215 doesn't expose read/writeComponent.
                    // Serialize to json string instead.
                    // 1.21.1 requires a registry provider for (de)serialization.
                    String json = r.line() == null ? "\"\"" : Component.Serializer.toJson(r.line(), buf.registryAccess());
                    buf.writeUtf(json, 32767);
                    buf.writeBoolean(r.aggregated());
                    long[] ids = r.rawIds() == null ? new long[0] : r.rawIds();
                    buf.writeVarInt(ids.length);
                    for (long id : ids) buf.writeVarLong(id);
                    List<Component> groupedLines = r.groupedLines() == null ? List.of() : r.groupedLines();
                    buf.writeVarInt(groupedLines.size());
                    for (Component groupedLine : groupedLines) {
                        String groupedJson = groupedLine == null
                                ? "\"\""
                                : Component.Serializer.toJson(groupedLine, buf.registryAccess());
                        buf.writeUtf(groupedJson, 32767);
                    }
                }
            },
            buf -> {
                String title = buf.readUtf(32767);
                int pageIndex = buf.readVarInt();
                boolean hasPrev = buf.readBoolean();
                boolean hasNext = buf.readBoolean();
                int n = buf.readVarInt();
                List<LogRow> rows = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    long id = buf.readVarLong();
                    String dim = buf.readUtf(32767);
                    int x = buf.readVarInt();
                    int y = buf.readVarInt();
                    int z = buf.readVarInt();
                    String json = buf.readUtf(32767);
                    Component line;
                    try {
                        line = Component.Serializer.fromJson(json, buf.registryAccess());
                    } catch (Throwable t) {
                        line = Component.literal(json);
                    }
                    boolean aggregated = buf.readBoolean();
                    int m = buf.readVarInt();
                    long[] ids = new long[Math.max(0, m)];
                    for (int j = 0; j < ids.length; j++) ids[j] = buf.readVarLong();
                    int groupedCount = buf.readVarInt();
                    List<Component> groupedLines = new ArrayList<>(Math.max(0, groupedCount));
                    for (int j = 0; j < groupedCount; j++) {
                        String groupedJson = buf.readUtf(32767);
                        try {
                            Component parsed = Component.Serializer.fromJson(groupedJson, buf.registryAccess());
                            groupedLines.add(parsed == null ? Component.empty() : parsed);
                        } catch (Throwable t) {
                            groupedLines.add(Component.literal(groupedJson));
                        }
                    }
                    rows.add(new LogRow(id, dim, x, y, z, line, aggregated, ids, groupedLines));
                }
                return new S2CLogPagePayload(title, pageIndex, hasPrev, hasNext, List.copyOf(rows));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
