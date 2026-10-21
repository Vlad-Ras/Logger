package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.SignBlockEntity;

/** Captures edits when they happen, including direct mod calls to the native sign setter. */
public final class SignTextAudit {
    private SignTextAudit(){}
    public static CompoundTag before(SignBlockEntity sign){
        if(!(sign.getLevel() instanceof ServerLevel level)||!level.getServer().isSameThread()||CartAuditContext.restoring())return null;
        boolean structural=CreateStructuralHooks.before(level,sign.getBlockPos());
        boolean deployer=CreateDeployerHooks.before(level,sign.getBlockPos());
        if(structural||deployer||!LoggerConfig.isEnabled()||!LoggerConfig.VALUES.logBlocks.get())return null;
        return NbtSerde.snapshotBlockEntity(level,sign);
    }
    public static boolean changed(CompoundTag before,CompoundTag after){
        if(before==null||after==null)return false;
        for(String side:new String[]{"front_text","back_text"}){
            var b=before.getCompound(side);var a=after.getCompound(side);
            if(!java.util.Objects.equals(b.get("messages"),a.get("messages"))||!java.util.Objects.equals(b.get("filtered_messages"),a.get("filtered_messages")))return true;
        }
        return false;
    }
    public static void after(SignBlockEntity sign,CompoundTag before){
        if(before==null||!(sign.getLevel() instanceof ServerLevel level))return;
        var row=row(level,sign,before,NbtSerde.snapshotBlockEntity(level,sign));if(row!=null)LoggerRuntime.storage(level).append(row);
    }
    /** Builds a detached record without authorizing publication; supplied tags transfer ownership. */
    public static LogEntry row(ServerLevel level,SignBlockEntity sign,CompoundTag before,CompoundTag after){
        if(!changed(before,after))return null;
        var row=new LogEntry();row.ts=System.currentTimeMillis();row.dim=level.dimension().location().toString();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;
        var pos=sign.getBlockPos();row.x=pos.getX();row.y=pos.getY();row.z=pos.getZ();
        var cause=CauseContext.peek();if(cause!=null){row.actorUuid=cause.actorUuid();row.actorName=cause.actorName();}
        String source=MutationSourceResolver.resolveExternalSource();row.source=source==null?cause==null?"system:sign_edit":"player:sign_edit":source;
        if(row.actorName==null)row.actorName="SYSTEM";
        row.deferBlockBefore(sign.getBlockState());row.deferBlockAfter(sign.getBlockState());
        row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);row.extra="sign_text_edit";
        return row;
    }
}
