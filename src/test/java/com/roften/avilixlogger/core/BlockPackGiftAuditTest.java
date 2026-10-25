package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.blockpack.BlockPackGiftAudit;
import net.minecraft.nbt.CompoundTag;
import java.util.UUID;

public final class BlockPackGiftAuditTest {
    public static void run(){
        var actor=new BlockPackGiftAudit.Actor(UUID.randomUUID(),"GiftActor","minecraft:the_nether",-13,80,29);
        check(BlockPackGiftAudit.capture(actor,true,true)==null&&BlockPackGiftAudit.capture(null,true,false)==null,"no-op/missing actor fabricated setting event");
        var row=BlockPackGiftAudit.capture(actor,true,false);row.materializeSnapshots();
        var roundTrip=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);
        check(roundTrip.type==ActionType.GUI_CHANGE&&actor.id().equals(roundTrip.actorUuid)&&roundTrip.dim.equals(actor.dim())&&roundTrip.x==-13,"setting lost actor or source position");
        var before=NbtSerde.fromSnbt(roundTrip.beBefore);var after=NbtSerde.fromSnbt(roundTrip.beAfter);
        check(before.getBoolean("Value")&&!after.getBoolean("Value")&&after.getString("Scope").equals("server")&&after.getString("StorageDimension").equals("minecraft:overworld"),"global setting confused with actor dimension or lost actual values");
        check(before.getString("Key").equals("giftEnabled")&&roundTrip.extra.contains("true -> false"),"settings details/summary lost semantic key or transition");
        int mask=CaptureTypePolicy.mask();try{
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,2,false));
            var poison=(net.neoforged.neoforge.network.handling.IPayloadContext)java.lang.reflect.Proxy.newProxyInstance(BlockPackGiftAuditTest.class.getClassLoader(),new Class[]{net.neoforged.neoforge.network.handling.IPayloadContext.class},(p,m,a)->{throw new AssertionError("disabled capture called payload context");});
            check(BlockPackGiftAudit.begin(poison)==null&&!CaptureTypePolicy.allows(ActionType.GUI_CHANGE,row.source,null),"setting bypassed containers mask");
        }finally{CaptureTypePolicy.install(mask);}
        var preview=RollbackEngine.previewPrepared(new RollbackEngine.PreparedRollback(java.util.List.of(),java.util.List.of(),java.util.List.of(row),0));
        check(preview.applied==0&&preview.skippedReasons.getOrDefault("not_rollbackable",0)==1,"global setting entered partial block rollback");
        System.out.println("BLOCKPACK_GIFT_EVIDENCE_OK: no-op refusal, actor/global scope, detached before/after journal, disabled-category early gate and rollback refusal");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
