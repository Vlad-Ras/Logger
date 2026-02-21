package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> Server: indicates that the player has the client mod installed. */
public record C2SHelloPayload() implements CustomPacketPayload {
    public static final Type<C2SHelloPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "hello"));
    public static final StreamCodec<RegistryFriendlyByteBuf, C2SHelloPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {},
            buf -> new C2SHelloPayload()
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
