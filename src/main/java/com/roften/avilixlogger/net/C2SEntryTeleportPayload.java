package com.roften.avilixlogger.net;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
/** The server resolves coordinates from the entry; the client supplies no destination. */
public record C2SEntryTeleportPayload(long request,long entryId) implements CustomPacketPayload {
    public static final Type<C2SEntryTeleportPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath("avilixlogger","entry_teleport"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SEntryTeleportPayload> STREAM_CODEC=StreamCodec.of(
        (buf,p)->{buf.writeVarLong(p.request());buf.writeVarLong(p.entryId());},buf->new C2SEntryTeleportPayload(buf.readVarLong(),buf.readVarLong()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
