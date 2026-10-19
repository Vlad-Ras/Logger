package com.roften.avilixlogger.net;
import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record S2CLoggerFunctionPayload(long request,String text) implements CustomPacketPayload {
    public static final Type<S2CLoggerFunctionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"gui_function_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CLoggerFunctionPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeLong(p.request);b.writeUtf(p.text,8192);},b->new S2CLoggerFunctionPayload(b.readLong(),b.readUtf(8192)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
