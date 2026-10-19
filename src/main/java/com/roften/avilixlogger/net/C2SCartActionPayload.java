package com.roften.avilixlogger.net;

import com.roften.avilixlogger.AvilixLoggerMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import java.util.*;

/** Explicit GUI operations. Never accepts or executes arbitrary command text. */
public record C2SCartActionPayload(Action action,String cartId,long entry) implements CustomPacketPayload {
    public enum Action {
        HELD, RECOVER, RESTORE_HELD, RESTORE_WORLD, ROLLBACK, CONFIRM_RECOVERY, CONFIRM_ROLLBACK, CANCEL_RECOVERY, CANCEL_ROLLBACK, STATUS, CLOSE;
        public boolean restorePermission(){return this==RECOVER||this==RESTORE_HELD||this==RESTORE_WORLD||this==ROLLBACK||this==CONFIRM_RECOVERY||this==CONFIRM_ROLLBACK;}
        public boolean needsId(){return restorePermission();}
    }
    public C2SCartActionPayload {
        Objects.requireNonNull(action);cartId=cartId==null?"":cartId;
        if(action.needsId())cartId=UUID.fromString(cartId).toString();
        else if(!cartId.isEmpty())throw new IllegalArgumentException("Лишний ID операции");
        if(entry<0||action.needsId()&&action!=Action.RECOVER&&action!=Action.RESTORE_HELD&&action!=Action.RESTORE_WORLD&&entry==0||!action.needsId()&&entry!=0)throw new IllegalArgumentException("Неверный entry операции");
    }
    /** Compatibility with the server's existing preview builder; only these two exact forms are allowed. */
    public static C2SCartActionPayload confirmation(String value){
        String[] p=value.split(" ");if(p.length!=5||!p[0].equals("log")||!p[1].equals("cart"))throw new IllegalArgumentException("Неверное подтверждение");
        var action=switch(p[2]){case "confirm-recovery"->Action.CONFIRM_RECOVERY;case "confirm"->Action.CONFIRM_ROLLBACK;default->throw new IllegalArgumentException("Неизвестное подтверждение");};
        return new C2SCartActionPayload(action,p[3],Long.parseLong(p[4]));
    }
    public static final Type<C2SCartActionPayload> TYPE=new Type<>(ResourceLocation.fromNamespaceAndPath(AvilixLoggerMod.MOD_ID,"cart_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf,C2SCartActionPayload> STREAM_CODEC=StreamCodec.of((b,p)->{b.writeEnum(p.action);b.writeUtf(p.cartId,36);b.writeLong(p.entry);},b->new C2SCartActionPayload(b.readEnum(Action.class),b.readUtf(36),b.readLong()));
    public Type<? extends CustomPacketPayload> type(){return TYPE;}
}
