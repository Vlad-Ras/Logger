package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.blockpack.BlockPackJoinGiftAudit;
import net.minecraft.nbt.CompoundTag;
import java.util.*;

public final class ItemTransactionAuditTest {
    public static void run(){
        var recipient=new ItemTransactionAudit.Party(UUID.randomUUID(),"GiftRecipient","minecraft:player","minecraft:the_nether",-13,80,29);
        var merchant=new ItemTransactionAudit.Party(UUID.randomUUID(),"MerchantName","bf_blockpack:wandering_trader","minecraft:the_nether",40,70,-2);
        var requested=stack("bf_blockpack:workbench",8);var dropped=stack("bf_blockpack:workbench",5);
        var drop=new ItemTransactionAudit.Drop(UUID.randomUUID(),true,dropped,recipient.dim(),-12.125,81.25,30.5);
        var gift=ItemTransactionAudit.gift(recipient,requested,3,drop,true);requested.putInt("count",99);dropped.putInt("count",99);
        gift.materializeSnapshots();var round=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(gift),LogEntry.class);var data=NbtSerde.fromSnbt(round.beAfter);
        check(round.actorUuid==null&&round.actorName.startsWith("SYSTEM")&&round.type==ActionType.ITEM_GIFT&&round.count==8,"automatic gift attributed to recipient or lost delivered quantities");
        check(round.dim.equals(recipient.dim())&&round.x==-13&&data.getCompound("Recipient").getUUID("UUID").equals(recipient.id()),"gift recipient or actual dimension/position lost");
        check(data.getInt("Inserted")==3&&data.getInt("Dropped")==5&&data.getCompound("Requested").getInt("count")==8&&data.getCompound("WorldDrop").getCompound("Item").getInt("count")==5,"gift receipt retained mutable item compounds");
        check(data.getCompound("WorldDrop").getDouble("X")==-12.125&&data.getInt("Unaccounted")==0,"gift world drop identity/position or accounting lost");
        var refused=ItemTransactionAudit.gift(recipient,stack("bf_blockpack:guide_book",1),0,new ItemTransactionAudit.Drop(UUID.randomUUID(),false,stack("bf_blockpack:guide_book",1),recipient.dim(),1,2,3),true);refused.materializeSnapshots();
        check(refused.count==0&&NbtSerde.fromSnbt(refused.beAfter).getInt("Unaccounted")==1,"refused world spawn became delivered gift");
        check(ItemTransactionAudit.gift(recipient,stack("bf_blockpack:workbench",1),2,null,true)==null&&ItemTransactionAudit.gift(null,stack("bf_blockpack:workbench",1),1,null,true)==null,"invalid gift quantities/recipient accepted");
        var trade=ItemTransactionAudit.trade(recipient,merchant,stack("minecraft:emerald",3),stack("minecraft:diamond",1),stack("bf_blockpack:workbench",2),7);trade.materializeSnapshots();var traded=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(trade),LogEntry.class);
        check(traded.type==ActionType.ITEM_TRADE&&traded.actorUuid.equals(recipient.id())&&traded.actorName.equals(recipient.name())&&traded.count==2&&traded.x==40&&traded.z==-2,"trade lost buyer or used buyer position for merchant");
        var t=NbtSerde.fromSnbt(traded.beAfter);check(t.getCompound("Merchant").getUUID("UUID").equals(merchant.id())&&t.getCompound("CostA").getInt("count")==3&&t.getCompound("CostB").getInt("count")==1&&t.getInt("UsesAfter")==7,"trade costs/result/vendor/uses lost");
        check(ItemTransactionAudit.trade(recipient,merchant,stack("minecraft:emerald",3),null,stack("bf_blockpack:workbench",1),0)==null,"unused offer became completed trade");
        String details=ItemTransactionAudit.describe(round).stream().map(net.minecraft.network.chat.Component::getString).collect(java.util.stream.Collectors.joining("\n"));
        check(details.contains(recipient.name())&&details.contains(drop.id().toString())&&details.contains("В инвентаре: 3"),"gift details hide recipient/delivery");
        details=ItemTransactionAudit.describe(traded).stream().map(net.minecraft.network.chat.Component::getString).collect(java.util.stream.Collectors.joining("\n"));check(details.contains(merchant.name())&&details.contains("minecraft:diamond ×1")&&details.contains("minecraft:emerald ×3"),"trade details hide merchant or second cost");
        var corrupt=new LogEntry();corrupt.type=ActionType.ITEM_TRADE;corrupt.beAfter="{";check(ItemTransactionAudit.describe(corrupt).isEmpty(),"corrupt receipt broke detail fallback");
        int old=CaptureTypePolicy.mask();try{CaptureTypePolicy.install(CaptureTypePolicy.changed(0,4,false));check(!CaptureTypePolicy.allows(ActionType.ITEM_GIFT,gift.source,null)&&!CaptureTypePolicy.allows(ActionType.ITEM_TRADE,trade.source,null),"transactions bypassed items preset");check(BlockPackJoinGiftAudit.begin(null,null)==null&&!BlockPackJoinGiftAudit.active(),"disabled gift capture touched player/stack or retained scope");ItemTransactionAudit.onTrade(null);}finally{CaptureTypePolicy.install(old);}
        var preview=RollbackEngine.previewPrepared(new RollbackEngine.PreparedRollback(List.of(),List.of(),List.of(round,traded),0));check(preview.applied==0&&preview.skippedReasons.getOrDefault("not_rollbackable",0)==2,"transaction entered partial world/inventory rollback");
        check(!LogText.actionLabel(ActionType.ITEM_GIFT).equals("сделал")&&!LogText.actionLabel(ActionType.ITEM_TRADE).equals("сделал"),"transaction action label absent");
        System.out.println("ITEM_TRANSACTION_EVIDENCE_OK: detached gift/trade receipts, actual targets, split inventory/drop, rejected spawn, two costs, exact actor/recipient, journal/details, disabled gate and no partial rollback");
    }
    public static CompoundTag stack(String id,int count){var tag=new CompoundTag();tag.putString("id",id);tag.putInt("count",count);return tag;}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
