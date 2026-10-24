package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Authoritative server state after a query or mutation (including permission errors). */
public record S2CCaptureTypesPayload(int disabledMask,int revision,boolean canEdit,boolean success,String message)
        implements CustomPacketPayload {
    public static final Type<S2CCaptureTypesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "capture_types_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CCaptureTypesPayload> STREAM_CODEC = StreamCodec.of(
            (buf,p) -> {
                buf.writeVarInt(p.disabledMask);buf.writeVarInt(p.revision);buf.writeBoolean(p.canEdit);
                buf.writeBoolean(p.success);buf.writeUtf(p.message==null?"":p.message,512);
            },
            buf -> new S2CCaptureTypesPayload(buf.readVarInt(),buf.readVarInt(),buf.readBoolean(),buf.readBoolean(),buf.readUtf(512)));
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
