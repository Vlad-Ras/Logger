package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> Client: instruct client to open the Log Viewer screen. */
public record S2COpenGuiPayload() implements CustomPacketPayload {
    public static final Type<S2COpenGuiPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "open_gui"));
    public static final StreamCodec<RegistryFriendlyByteBuf, S2COpenGuiPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {},
            buf -> new S2COpenGuiPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
