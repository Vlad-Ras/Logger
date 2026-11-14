package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Small status packet: no mod names, local paths or file contents are transferred. */
public final class ClientReportStatusPayloads {
    private ClientReportStatusPayloads() {}

    public enum Stage { COLLECTING, FAILED }

    public record Status(long id, Stage stage) implements CustomPacketPayload {
        public static final Type<Status> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "client_report_status"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Status> CODEC = StreamCodec.of(
                (buf, status) -> {
                    buf.writeLong(status.id());
                    buf.writeEnum(status.stage());
                },
                buf -> new Status(buf.readLong(), buf.readEnum(Stage.class)));
        @Override public Type<Status> type() { return TYPE; }
    }
}
