package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> Server: request a page for the GUI (nav: FIRST/PREV/NEXT), with mode toggle. */
public record C2SRequestPagePayload(Nav nav, boolean aggregated, GuiFilters filters, int pageSize) implements CustomPacketPayload {

    public static final int DEFAULT_PAGE_SIZE = 60;
    public static final int MIN_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 400;

    public enum Nav { FIRST, PREV, NEXT, SAME }

    public static final Type<C2SRequestPagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID, "gui_page"));

    public static final StreamCodec<RegistryFriendlyByteBuf, C2SRequestPagePayload> STREAM_CODEC = StreamCodec.of(
            (buf, msg) -> {
                buf.writeByte(msg.nav.ordinal());
                buf.writeBoolean(msg.aggregated);
                GuiFilters.STREAM_CODEC.encode(buf, msg.filters == null ? GuiFilters.DEFAULT : msg.filters);
                buf.writeVarInt(clampPageSize(msg.pageSize));
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
                int pageSize = DEFAULT_PAGE_SIZE;
                try {
                    if (buf.readableBytes() > 0) pageSize = buf.readVarInt();
                } catch (Throwable ignored) {
                    // backward compat with clients that do not send the per-page setting
                }
                return new C2SRequestPagePayload(nav, aggregated, filters, clampPageSize(pageSize));
            }
    );

    public static int clampPageSize(int pageSize) {
        return Math.max(MIN_PAGE_SIZE, Math.min(MAX_PAGE_SIZE, pageSize));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
