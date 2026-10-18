package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> Client: instruct client to open the Log Viewer screen. */
public record S2COpenGuiPayload(String timeZone) implements CustomPacketPayload {
    public S2COpenGuiPayload() { this(java.time.ZoneId.systemDefault().getId()); }
    public static final Type<S2COpenGuiPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "open_gui"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenGuiPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> buf.writeUtf(msg.timeZone, 64),
            buf -> new S2COpenGuiPayload(buf.readUtf(64))
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
