package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> Server: request to open the Log Viewer GUI (server will check permission & client presence). */
public record C2SOpenGuiPayload() implements CustomPacketPayload {

    public static final Type<C2SOpenGuiPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_open"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SOpenGuiPayload> STREAM_CODEC = StreamCodec.unit(new C2SOpenGuiPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
