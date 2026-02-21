package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> Server: request a page for the GUI (nav: FIRST/PREV/NEXT), with mode toggle. */
public record C2SRequestPagePayload(Nav nav, boolean aggregated, GuiFilters filters) implements CustomPacketPayload {

    public enum Nav { FIRST, PREV, NEXT, SAME }

    public static final Type<C2SRequestPagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_page"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestPagePayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeByte(msg.nav.ordinal());
                buf.writeBoolean(msg.aggregated);
                GuiFilters.STREAM_CODEC.encode(buf, msg.filters == null ? GuiFilters.DEFAULT : msg.filters);
            },
            buf -> {
                int o = buf.readByte();
                Nav nav = (o >= 0 && o < Nav.values().length) ? Nav.values()[o] : Nav.FIRST;
                boolean aggregated = true;
                try {
                    aggregated = buf.readBoolean();
                } catch (Throwable ignored) {
                    // backward compat
                }
                GuiFilters filters = GuiFilters.DEFAULT;
                try {
                    filters = GuiFilters.STREAM_CODEC.decode(buf);
                } catch (Throwable ignored) {
                    // backward compat
                }
                return new C2SRequestPagePayload(nav, aggregated, filters);
            }
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
