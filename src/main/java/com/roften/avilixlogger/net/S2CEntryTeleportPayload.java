package com.roften.avilixlogger.net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** Typed result: progress text is never mistaken for successful movement. */
public record S2CEntryTeleportPayload(long request,State state,String message) implements CustomPacketPayload {
    public enum State { PREPARING, SUCCESS, FAILURE }
    public static final Type<S2CEntryTeleportPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("avilixlogger","entry_teleport_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CEntryTeleportPayload> STREAM_CODEC=StreamCodec.of(
        (buf,p)->{buf.writeVarLong(p.request());buf.writeEnum(p.state());buf.writeUtf(p.message(),2048);},
        buf->new S2CEntryTeleportPayload(buf.readVarLong(),buf.readEnum(State.class),buf.readUtf(2048)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
