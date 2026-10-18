package com.roften.avilixlogger.net;
import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record C2SCartHistoryPayload(long request,String cartId,long before,String actor,boolean checkpointsOnly) implements CustomPacketPayload {
    public C2SCartHistoryPayload(long request,String cartId,long before){this(request,cartId,before,"",false);}
    public C2SCartHistoryPayload {if(cartId==null||actor==null||cartId.length()>36||actor.length()>64||before<0)throw new IllegalArgumentException("Неверные фильтры истории");}
    public static final Type<C2SCartHistoryPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"cart_history_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SCartHistoryPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeLong(p.request);b.writeUtf(p.cartId,36);b.writeLong(p.before);b.writeUtf(p.actor,64);b.writeBoolean(p.checkpointsOnly);},b->new C2SCartHistoryPayload(b.readLong(),b.readUtf(36),b.readLong(),b.readUtf(64),b.readBoolean()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
