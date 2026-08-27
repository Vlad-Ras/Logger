package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: show or clear the exact finite area of the pending rollback plan. */
public record S2CRollbackPreviewPayload(
        boolean visible,
        String planId,
        String dimension,
        int minX, int minY, int minZ,
        int maxX, int maxY, int maxZ,
        long targetTs,
        long expiresAt
) implements CustomPacketPayload {

    public static final Type<S2CRollbackPreviewPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "rollback_preview")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CRollbackPreviewPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeBoolean(msg.visible);
                buf.writeUtf(msg.planId == null ? "" : msg.planId, 32);
                buf.writeUtf(msg.dimension == null ? "" : msg.dimension, 256);
                buf.writeInt(msg.minX);
                buf.writeInt(msg.minY);
                buf.writeInt(msg.minZ);
                buf.writeInt(msg.maxX);
                buf.writeInt(msg.maxY);
                buf.writeInt(msg.maxZ);
                buf.writeLong(msg.targetTs);
                buf.writeLong(msg.expiresAt);
            },
            buf -> new S2CRollbackPreviewPayload(
                    buf.readBoolean(),
                    buf.readUtf(32),
                    buf.readUtf(256),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readLong(), buf.readLong())
    );

    public static S2CRollbackPreviewPayload clear() {
        return new S2CRollbackPreviewPayload(false, "", "", 0, 0, 0, 0, 0, 0, 0L, 0L);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
