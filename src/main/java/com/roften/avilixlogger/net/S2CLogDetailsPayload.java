package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/** Server -> Client: details or raw rows for a selection in the GUI. */
public record S2CLogDetailsPayload(long entryId, C2SRequestDetailsPayload.Mode mode, List<Component> lines) implements CustomPacketPayload {

    public static final Type<S2CLogDetailsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_details_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CLogDetailsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeVarLong(msg.entryId);
                buf.writeByte(msg.mode.ordinal());
                List<Component> lines = msg.lines == null ? List.of() : msg.lines;
                buf.writeVarInt(lines.size());
                    for (Component c : lines) {
                        String json = c == null ? "\"\"" : Component.Serializer.toJson(c, buf.registryAccess());
                    buf.writeUtf(json, 32767);
                }
            },
            buf -> {
                long entryId = buf.readVarLong();
                int o = buf.readByte();
                C2SRequestDetailsPayload.Mode mode = (o >= 0 && o < C2SRequestDetailsPayload.Mode.values().length)
                        ? C2SRequestDetailsPayload.Mode.values()[o]
                        : C2SRequestDetailsPayload.Mode.DETAILS;
                int n = buf.readVarInt();
                List<Component> lines = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    String json = buf.readUtf(32767);
                    Component c;
                    try {
                        c = Component.Serializer.fromJson(json, buf.registryAccess());
                    } catch (Throwable t) {
                        c = Component.literal(json);
                    }
                    lines.add(c);
                }
                return new S2CLogDetailsPayload(entryId, mode, List.copyOf(lines));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
