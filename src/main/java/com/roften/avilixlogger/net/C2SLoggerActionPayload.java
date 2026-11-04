package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.List;

public record C2SLoggerActionPayload(long request,String action,List<String> values) implements CustomPacketPayload {
    public C2SLoggerActionPayload { values=List.copyOf(values);if(values.size()>8)throw new IllegalArgumentException("Too many fields"); }
    public static final Type<C2SLoggerActionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"logger_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SLoggerActionPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeLong(p.request);b.writeUtf(p.action,48);b.writeVarInt(p.values.size());for(String value:p.values)b.writeUtf(value,512);},
        b->{long request=b.readLong();String action=b.readUtf(48);int count=b.readVarInt();if(count<0||count>8)throw new IllegalArgumentException("Too many fields");var values=new java.util.ArrayList<String>(count);for(int i=0;i<count;i++)values.add(b.readUtf(512));return new C2SLoggerActionPayload(request,action,values);});
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
