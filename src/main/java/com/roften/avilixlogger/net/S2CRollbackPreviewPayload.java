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
        long expiresAt,
        java.util.List<Marker> markers
) implements CustomPacketPayload {

    public static final int MAX_MARKERS = 512;
    public enum Kind { BLOCK, REMOVE, CONTAINER, ENTITY }
    public record Marker(net.minecraft.core.BlockPos pos, Kind kind, String id) {}

    public S2CRollbackPreviewPayload {
        if (markers.size() > MAX_MARKERS) throw new IllegalArgumentException("Too many rollback preview markers");
        markers = java.util.List.copyOf(markers);
    }

    public S2CRollbackPreviewPayload(boolean visible, String planId, String dimension, int minX, int minY, int minZ,
                                    int maxX, int maxY, int maxZ, long targetTs, long expiresAt) {
        this(visible, planId, dimension, minX, minY, minZ, maxX, maxY, maxZ, targetTs, expiresAt, java.util.List.of());
    }

    public static final Type<S2CRollbackPreviewPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "rollback_preview_v2")
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
                buf.writeVarInt(msg.markers.size());
                for (Marker marker : msg.markers) {
                    buf.writeBlockPos(marker.pos());
                    buf.writeEnum(marker.kind());
                    buf.writeUtf(marker.id(), 256);
                }
            },
            S2CRollbackPreviewPayload::decode
    );

    private static S2CRollbackPreviewPayload decode(RegistryFriendlyByteBuf buf) {
        boolean visible = buf.readBoolean();
        String plan = buf.readUtf(32);
        String dimension = buf.readUtf(256);
        int minX = buf.readInt(), minY = buf.readInt(), minZ = buf.readInt();
        int maxX = buf.readInt(), maxY = buf.readInt(), maxZ = buf.readInt();
        long target = buf.readLong(), expires = buf.readLong();
        int count = buf.readVarInt();
        if (count < 0 || count > MAX_MARKERS) throw new IllegalArgumentException("Invalid rollback preview marker count");
        var markers = new java.util.ArrayList<Marker>(count);
        for (int i = 0; i < count; i++) markers.add(new Marker(buf.readBlockPos(), buf.readEnum(Kind.class), buf.readUtf(256)));
        return new S2CRollbackPreviewPayload(visible, plan, dimension, minX, minY, minZ, maxX, maxY, maxZ, target, expires, markers);
    }

    public static S2CRollbackPreviewPayload clear() {
        return new S2CRollbackPreviewPayload(false, "", "", 0, 0, 0, 0, 0, 0, 0L, 0L);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
