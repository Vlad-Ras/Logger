package com.roften.avilixlogger.net;
import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;
/** Lightweight rows only: full NBT stays on the server. */
public record S2CCartHistoryPayload(int kind,long request,String cartId,long next,boolean canRestore,String message,String command,List<Row> rows) implements CustomPacketPayload {
    public record Row(String cartId,long entry,String time,String action,String actor,String location,String detail,boolean checkpoint,boolean snapshot){}
    public static final int OPEN=0,PAGE=1,CONFIRM=2,ERROR=3,NOTICE=4,PROGRESS=5,DONE=6;
    public static final Type<S2CCartHistoryPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"cart_history"));
    public static final StreamCodec<RegistryFriendlyByteBuf,S2CCartHistoryPayload> STREAM_CODEC=StreamCodec.of((b,p)->{
        b.writeByte(p.kind);b.writeLong(p.request);b.writeUtf(p.cartId,36);b.writeLong(p.next);b.writeBoolean(p.canRestore);b.writeUtf(p.message,4096);b.writeUtf(p.command,256);b.writeVarInt(p.rows.size());
        for(var r:p.rows){b.writeUtf(r.cartId,36);b.writeLong(r.entry);b.writeUtf(r.time,64);b.writeUtf(r.action,128);b.writeUtf(r.actor,128);b.writeUtf(r.location,256);b.writeUtf(r.detail,4096);b.writeBoolean(r.checkpoint);b.writeBoolean(r.snapshot);}
    },b->{int kind=b.readUnsignedByte();long request=b.readLong();String id=b.readUtf(36);long next=b.readLong();boolean restore=b.readBoolean();String message=b.readUtf(4096),command=b.readUtf(256);int count=b.readVarInt();
        if(kind>DONE||count<0||count>12)throw new IllegalArgumentException("Invalid cart page");var rows=new ArrayList<Row>();
        for(int i=0;i<count;i++)rows.add(new Row(b.readUtf(36),b.readLong(),b.readUtf(64),b.readUtf(128),b.readUtf(128),b.readUtf(256),b.readUtf(4096),b.readBoolean(),b.readBoolean()));
        return new S2CCartHistoryPayload(kind,request,id,next,restore,message,command,List.copyOf(rows));});
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
