package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record S2CLoggerActionResultPayload(long request,String message) implements CustomPacketPayload {
    public static final Type<S2CLoggerActionResultPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"logger_action_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CLoggerActionResultPayload> STREAM_CODEC=StreamCodec.of(
        (b,p)->{b.writeLong(p.request);b.writeUtf(p.message,8000);},b->new S2CLoggerActionResultPayload(b.readLong(),b.readUtf(8000)));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
