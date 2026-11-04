package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Query all current settings, toggle one type, or enable/disable all. */
public record C2SCaptureTypesPayload(Action action, int preset, boolean enabled, int expectedRevision) implements CustomPacketPayload {
    public enum Action { QUERY, SET, ENABLE_ALL, DISABLE_ALL }
    public static final Type<C2SCaptureTypesPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "capture_types_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SCaptureTypesPayload> STREAM_CODEC = StreamCodec.of(
            (buf,p) -> { buf.writeVarInt(p.action.ordinal()); buf.writeVarInt(p.preset); buf.writeBoolean(p.enabled); buf.writeVarInt(p.expectedRevision); },
            buf -> {
                int kind = buf.readVarInt();
                if (kind < 0 || kind >= Action.values().length) throw new IllegalArgumentException("Invalid capture action");
                return new C2SCaptureTypesPayload(Action.values()[kind],buf.readVarInt(),buf.readBoolean(),buf.readVarInt());
            });
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
