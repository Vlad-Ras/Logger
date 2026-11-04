package com.roften.avilixlogger.net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Administrator-only bounded GUI warning. */
public record S2CCaptureHealthPayload(boolean paused,String message) implements CustomPacketPayload {
    public static final Type<S2CCaptureHealthPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("avilixlogger","capture_health"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CCaptureHealthPayload> STREAM_CODEC=StreamCodec.of(
        (buf,p)->{buf.writeBoolean(p.paused());buf.writeUtf(p.message(),2048);},buf->new S2CCaptureHealthPayload(buf.readBoolean(),buf.readUtf(2048)));
    @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
