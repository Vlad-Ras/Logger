package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: current inspect-tool setting and the result of an optional update. */
public record S2CInspectToolPayload(String itemId, boolean canEdit, boolean success, Component message)
        implements CustomPacketPayload {

    public static final Type<S2CInspectToolPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_inspect_tool_data")
    );

    public static final StreamCodec<RegistryFriendlyByteBuf, S2CInspectToolPayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeUtf(msg.itemId == null ? "" : msg.itemId, 256);
                buf.writeBoolean(msg.canEdit);
                buf.writeBoolean(msg.success);
                String json = msg.message == null
                        ? "\"\""
                        : Component.Serializer.toJson(msg.message, buf.registryAccess());
                buf.writeUtf(json, 32767);
            },
            buf -> {
                String itemId = buf.readUtf(256);
                boolean canEdit = buf.readBoolean();
                boolean success = buf.readBoolean();
                String json = buf.readUtf(32767);
                Component message;
                try {
                    message = Component.Serializer.fromJson(json, buf.registryAccess());
                } catch (Throwable ignored) {
                    message = Component.literal(json);
                }
                return new S2CInspectToolPayload(itemId, canEdit, success, message);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
