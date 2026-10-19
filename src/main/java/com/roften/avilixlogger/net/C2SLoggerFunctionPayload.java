package com.roften.avilixlogger.net;
import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record C2SLoggerFunctionPayload(long request,LoggerGuiOperation operation,String first,String second) implements CustomPacketPayload {
    public C2SLoggerFunctionPayload{if(operation==null)throw new IllegalArgumentException();operation.command(first,second);}
    public static final Type<C2SLoggerFunctionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"gui_function"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SLoggerFunctionPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeLong(p.request);b.writeEnum(p.operation);b.writeUtf(p.first,1024);b.writeUtf(p.second,1024);},b->new C2SLoggerFunctionPayload(b.readLong(),b.readEnum(LoggerGuiOperation.class),b.readUtf(1024),b.readUtf(1024)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
