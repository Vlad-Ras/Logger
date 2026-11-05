package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Authoritative server state after a query or mutation (including permission errors). */
public record S2CCaptureTypesPayload(long disabledMask,int revision,boolean canEdit,boolean success,String message,java.util.List<String> disabledHandlers)
        implements CustomPacketPayload {
    public S2CCaptureTypesPayload(long mask, int revision, boolean canEdit, boolean success, String message) {
        this(mask, revision, canEdit, success, message, java.util.List.of());
    }
    public static final Type<S2CCaptureTypesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "capture_types_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CCaptureTypesPayload> STREAM_CODEC = StreamCodec.of(
            (buf,p) -> {
                buf.writeVarLong(p.disabledMask);buf.writeVarInt(p.revision);buf.writeBoolean(p.canEdit);
                buf.writeBoolean(p.success);buf.writeUtf(p.message==null?"":p.message,512);
                buf.writeVarInt(p.disabledHandlers.size());
                for (var key : p.disabledHandlers) buf.writeUtf(key, 256);
            },
            buf -> {
                long mask = buf.readVarLong();
                int revision = buf.readVarInt();
                boolean canEdit = buf.readBoolean(), success = buf.readBoolean();
                String message = buf.readUtf(512);
                int size = buf.readVarInt();
                if (size < 0 || size > 2048) throw new IllegalArgumentException("Invalid capture handler count");
                var keys = new java.util.ArrayList<String>(size);
                for (int i = 0; i < size; i++) keys.add(buf.readUtf(256));
                return new S2CCaptureTypesPayload(mask, revision, canEdit, success, message, java.util.List.copyOf(keys));
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
