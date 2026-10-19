package com.roften.avilixlogger.net;
import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
public record C2SCartHistoryPayload(long request,String cartId,long before,String actor,boolean checkpointsOnly,String owner,String time,String actionFilter,String dimension,String text,int pageSize) implements CustomPacketPayload {
    public C2SCartHistoryPayload(long request,String cartId,long before,String actor,boolean checkpointsOnly){this(request,cartId,before,actor,checkpointsOnly,"","","ALL","*","",12);}
    public C2SCartHistoryPayload(long request,String cartId,long before){this(request,cartId,before,"",false);}
    public C2SCartHistoryPayload {if(cartId==null||actor==null||owner==null||time==null||actionFilter==null||dimension==null||text==null||cartId.length()>36||actor.length()>64||owner.length()>64||time.length()>96||actionFilter.length()>64||dimension.length()>128||text.length()>128||pageSize<1||pageSize>60||before<0)throw new IllegalArgumentException("Неверные фильтры истории");}
    public static final Type<C2SCartHistoryPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"cart_history_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SCartHistoryPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeLong(p.request);b.writeUtf(p.cartId,36);b.writeLong(p.before);b.writeUtf(p.actor,64);b.writeBoolean(p.checkpointsOnly);b.writeUtf(p.owner,64);b.writeUtf(p.time,96);b.writeUtf(p.actionFilter,64);b.writeUtf(p.dimension,128);b.writeUtf(p.text,128);b.writeVarInt(p.pageSize);},b->new C2SCartHistoryPayload(b.readLong(),b.readUtf(36),b.readLong(),b.readUtf(64),b.readBoolean(),b.readUtf(64),b.readUtf(96),b.readUtf(64),b.readUtf(128),b.readUtf(128),b.readVarInt()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
