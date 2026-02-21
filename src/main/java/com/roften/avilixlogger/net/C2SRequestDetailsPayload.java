package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> Server: request details for a selected GUI row. */
public record C2SRequestDetailsPayload(long entryId, Mode mode, long[] rawIds, String dimHint) implements CustomPacketPayload {

    public enum Mode { DETAILS, RAW, JSON }

    public static final Type<C2SRequestDetailsPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_details")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestDetailsPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeVarLong(msg.entryId);
                buf.writeByte(msg.mode.ordinal());
                long[] ids = msg.rawIds == null ? new long[0] : msg.rawIds;
                buf.writeVarInt(ids.length);
                for (long id : ids) buf.writeVarLong(id);
                buf.writeUtf(msg.dimHint == null ? "" : msg.dimHint, 32767);
            },
            buf -> {
                long entryId = buf.readVarLong();
                int o = buf.readByte();
                Mode mode = (o >= 0 && o < Mode.values().length) ? Mode.values()[o] : Mode.DETAILS;
                int n = buf.readVarInt();
                long[] ids = new long[Math.max(0, n)];
                for (int i = 0; i < ids.length; i++) ids[i] = buf.readVarLong();
                String dim = buf.readUtf(32767);
                return new C2SRequestDetailsPayload(entryId, mode, ids, dim);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
