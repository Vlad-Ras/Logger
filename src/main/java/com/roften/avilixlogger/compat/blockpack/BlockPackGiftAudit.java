package com.roften.avilixlogger.compat.blockpack;

import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import java.util.UUID;

/** Observe the existing packet and setter; never serialize the unbounded gift-recipient set. */
public final class BlockPackGiftAudit {
    private static final ThreadLocal<Action> CURRENT=new ThreadLocal<>();
    private BlockPackGiftAudit(){}
    public record Actor(UUID id,String name,String dim,int x,int y,int z){}
    public static Action begin(IPayloadContext context){
        if(!CaptureTypePolicy.enabled(2) || !LoggerConfig.isEnabled() || CartAuditContext.restoring())return null;
        try {
            if(!(context.player() instanceof ServerPlayer p) || !p.server.isSameThread())return null;
            var pos=p.blockPosition();var actor=new Actor(CauseContext.actorUuid(p),CauseContext.actorName(p),p.serverLevel().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());
            var action=new Action(p.serverLevel(),actor,CURRENT.get());CURRENT.set(action);return action;
        }catch(RuntimeException failure){AdaptiveLogDiagnostics.adapterFailure("blockpack-gifts");return null;}
    }
    public record Action(ServerLevel level,Actor actor,Action parent) implements AutoCloseable {
        public void close(){if(parent==null)CURRENT.remove();else CURRENT.set(parent);}
    }
    public static void changed(boolean before,boolean after){
        if(before==after)return;var action=CURRENT.get();
        if(action==null || !action.level.getServer().isSameThread() || !CaptureTypePolicy.enabled(2) || !LoggerConfig.isEnabled())return;
        try{LoggerRuntime.storage(action.level).append(capture(action.actor,before,after));}
        catch(RuntimeException failure){AdaptiveLogDiagnostics.adapterFailure("blockpack-gifts");}
    }
    /** Detached evidence kernel: no world access, authorization, publication or partial rollback. */
    public static LogEntry capture(Actor actor,boolean before,boolean after){
        if(actor==null || before==after)return null;
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=ActionType.GUI_CHANGE;row.count=1;
        row.actorUuid=actor.id;row.actorName=actor.name;row.dim=actor.dim;row.x=actor.x;row.y=actor.y;row.z=actor.z;
        row.source="mod:bf_blockpack:gifts";row.extra="BlockPack: подарки; область=сервер; giftEnabled: "+before+" -> "+after+"; audit only";
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,state(before));row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,state(after));return row;
    }
    private static CompoundTag state(boolean value){
        var tag=new CompoundTag();tag.putString("Kind","ServerSetting");tag.putInt("Version",1);tag.putString("Provider","bf_blockpack");
        tag.putString("Scope","server");tag.putString("StorageDimension","minecraft:overworld");tag.putString("Key","giftEnabled");tag.putBoolean("Value",value);return tag;
    }
}
