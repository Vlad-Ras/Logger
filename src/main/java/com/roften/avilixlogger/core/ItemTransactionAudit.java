package com.roften.avilixlogger.core;

import com.roften.avilixlogger.LoggerConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import java.util.*;

/** Small detached receipts, not permission to capture or a partial inventory/economy rollback. */
public final class ItemTransactionAudit {
    private ItemTransactionAudit() {}
    public record Party(UUID id,String name,String type,String dim,int x,int y,int z) {
        public CompoundTag tag(){var t=new CompoundTag();t.putUUID("UUID",id);t.putString("Name",name);t.putString("Type",type);t.putString("Dimension",dim);t.putInt("X",x);t.putInt("Y",y);t.putInt("Z",z);return t;}
    }
    public record Drop(UUID id,boolean accepted,CompoundTag item,String dim,double x,double y,double z) {}
    public static boolean enabled(){return CaptureTypePolicy.enabled(4)&&LoggerConfig.isEnabled()&&LoggerConfig.VALUES.logItemTransactions.get()&&!CartAuditContext.restoring();}
    public static Party party(Entity entity){var pos=entity.blockPosition();return new Party(entity.getUUID(),entity instanceof Player p?p.getGameProfile().getName():EntityLogIdentity.name(entity),net.minecraft.world.entity.EntityType.getKey(entity.getType()).toString(),entity.level().dimension().location().toString(),pos.getX(),pos.getY(),pos.getZ());}
    public static LogEntry gift(Party recipient,CompoundTag requested,int inserted,Drop drop,boolean completed){
        int count=requested==null?0:requested.getInt("count");
        int dropped=drop!=null&&drop.accepted()&&drop.item()!=null?drop.item().getInt("count"):0;
        if(recipient==null||requested==null||count<=0||inserted<0||dropped<0||(long)inserted+dropped>count)return null;
        var data=envelope("ItemGift");data.put("Recipient",recipient.tag());data.put("Requested",requested.copy());data.putInt("Inserted",inserted);data.putInt("Dropped",dropped);data.putInt("Unaccounted",count-inserted-dropped);data.putBoolean("Completed",completed);data.putBoolean("DropAttempted",drop!=null);
        if(drop!=null){var d=new CompoundTag();if(drop.id()!=null)d.putUUID("UUID",drop.id());d.putBoolean("Accepted",drop.accepted());if(drop.item()!=null)d.put("Item",drop.item().copy());d.putString("Dimension",Objects.toString(drop.dim(),""));d.putDouble("X",drop.x());d.putDouble("Y",drop.y());d.putDouble("Z",drop.z());data.put("WorldDrop",d);}
        var row=base(ActionType.ITEM_GIFT,recipient,requested,inserted+dropped,data);row.actorName="SYSTEM[bf_blockpack]";row.source="mod:bf_blockpack:join_gift";
        row.extra="подарок BlockPack; получатель="+recipient.name()+"; инвентарь="+inserted+"; выпало="+dropped+"; не подтверждено="+(count-inserted-dropped)+"; завершено="+completed+"; audit only";return row;
    }
    public static LogEntry trade(Party buyer,Party merchant,CompoundTag first,CompoundTag second,CompoundTag result,int usesAfter){
        if(buyer==null||merchant==null||first==null||result==null||first.getInt("count")<=0||result.getInt("count")<=0||usesAfter<=0)return null;
        var data=envelope("ItemTrade");data.put("Buyer",buyer.tag());data.put("Merchant",merchant.tag());data.put("CostA",first.copy());if(second!=null)data.put("CostB",second.copy());data.put("Result",result.copy());data.putInt("UsesAfter",usesAfter);data.putString("Evidence","native TradeWithVillagerEvent; current offer costs");
        var row=base(ActionType.ITEM_TRADE,merchant,result,result.getInt("count"),data);row.actorUuid=buyer.id();row.actorName=buyer.name();row.source="mod:bf_blockpack:trade";
        row.extra="торговля BlockPack; продавец="+merchant.name()+"; цена="+itemLabel(first)+(second==null?"":" + "+itemLabel(second))+"; результат="+itemLabel(result)+"; audit only";return row;
    }
    public static void onTrade(TradeWithVillagerEvent event){
        if(!enabled()||event==null||!(event.getEntity() instanceof ServerPlayer player)||!player.server.isSameThread()||CauseContext.actorUuid(player)==null)return;
        var merchant=event.getAbstractVillager();
        if(merchant==null||!merchant.getClass().getName().equals("com.blockfactory.blockpack.entity.BfWanderingTrader"))return;
        if(net.neoforged.fml.ModList.get().getModContainerById("bf_blockpack").map(c->!c.getModInfo().getVersion().toString().equals("1.0.11")).orElse(true))return;
        try {
            var offer=event.getMerchantOffer();var access=player.registryAccess();
            var row=trade(party(player),party(merchant),item(offer.getCostA(),access),offer.getCostB().isEmpty()?null:item(offer.getCostB(),access),item(offer.getResult(),access),offer.getUses());
            if(row!=null)LoggerRuntime.storage(player.serverLevel()).append(row);
        }catch(RuntimeException failure){AdaptiveLogDiagnostics.adapterFailure("blockpack-trade");}
    }
    private static CompoundTag envelope(String kind){var data=new CompoundTag();data.putString("Kind",kind);data.putInt("Version",1);data.putString("Provider","bf_blockpack");return data;}
    private static LogEntry base(ActionType type,Party target,CompoundTag item,int count,CompoundTag data){var row=new LogEntry();row.ts=System.currentTimeMillis();row.type=type;row.dim=target.dim();row.x=target.x();row.y=target.y();row.z=target.z();row.entityUuid=target.id();row.entityName=target.name();row.entityType=target.type();row.count=count;row.deferSnapshot(LogEntry.SnapshotField.ITEM,item.copy());row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,data);return row;}
    public static CompoundTag item(net.minecraft.world.item.ItemStack stack,net.minecraft.core.HolderLookup.Provider access){var tag=NbtSerde.snapshotItemStack(stack,access);if(tag!=null)tag.putInt("count",stack.getCount());return tag;}
    public static String itemLabel(CompoundTag tag){return tag==null?"—":tag.getString("id")+" ×"+tag.getInt("count");}
    public static List<net.minecraft.network.chat.Component> describe(LogEntry row){
        if(row==null||(row.type!=ActionType.ITEM_GIFT&&row.type!=ActionType.ITEM_TRADE)||row.beAfter==null||row.beAfter.length()>1_048_576)return List.of();
        var data=NbtSerde.fromSnbt(row.beAfter);if(data==null||data.getInt("Version")!=1)return List.of();var text=new ArrayList<String>();
        if(row.type==ActionType.ITEM_GIFT&&data.getString("Kind").equals("ItemGift")){
            text.add("Получатель: "+partyLabel(data.getCompound("Recipient")));text.add("Запрошено: "+itemLabel(data.getCompound("Requested")));text.add("В инвентаре: "+data.getInt("Inserted")+" · выпало: "+data.getInt("Dropped")+" · не подтверждено: "+data.getInt("Unaccounted"));text.add("Операция завершена: "+data.getBoolean("Completed"));
            if(data.contains("WorldDrop")){var d=data.getCompound("WorldDrop");text.add("Выброс: "+(d.hasUUID("UUID")?d.getUUID("UUID"):"нет сущности")+" · принят миром: "+d.getBoolean("Accepted"));text.add("Место выброса: "+d.getString("Dimension")+" "+d.getDouble("X")+", "+d.getDouble("Y")+", "+d.getDouble("Z"));}
        }else if(row.type==ActionType.ITEM_TRADE&&data.getString("Kind").equals("ItemTrade")){
            text.add("Покупатель: "+partyLabel(data.getCompound("Buyer")));text.add("Продавец: "+partyLabel(data.getCompound("Merchant")));text.add("Цена предложения: "+itemLabel(data.getCompound("CostA"))+(data.contains("CostB")?" + "+itemLabel(data.getCompound("CostB")):""));text.add("Результат: "+itemLabel(data.getCompound("Result")));text.add("Использований после сделки: "+data.getInt("UsesAfter"));
        }
        return text.stream().map(s->(net.minecraft.network.chat.Component)net.minecraft.network.chat.Component.literal(s)).toList();
    }
    private static String partyLabel(CompoundTag t){return t.getString("Name")+" · "+(t.hasUUID("UUID")?t.getUUID("UUID"):"?")+" · "+t.getString("Dimension")+" "+t.getInt("X")+", "+t.getInt("Y")+", "+t.getInt("Z");}
}
