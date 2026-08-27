package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: query or update the inspect-tool item used by the logger. */
public record C2SInspectToolPayload(Action action, String itemId) implements CustomPacketPayload {

    public enum Action { QUERY, SET, RESET }

    public static final Type<C2SInspectToolPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_inspect_tool")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SInspectToolPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeByte(msg.action == null ? Action.QUERY.ordinal() : msg.action.ordinal());
                buf.writeUtf(msg.itemId == null ? "" : msg.itemId, 256);
            },
            buf -> {
                int ordinal = buf.readByte();
                Action action = ordinal >= 0 && ordinal < Action.values().length
                        ? Action.values()[ordinal]
                        : Action.QUERY;
                return new C2SInspectToolPayload(action, buf.readUtf(256));
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
