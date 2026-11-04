package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.trading.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.TradeWithVillagerEvent;
import java.util.*;
import java.util.function.Consumer;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Native MerchantResultSlot/event contract; optional mod is supplied by the caller. */
final class MerchantTransactionSmoke {
    static void run(ServerLevel level,AbstractVillager merchant,Item output)throws Exception{
        var buyer=ScgunsNativeSmoke.player(level,"TradeReceiptBuyer");buyer.setPos(-3.5,100,2.5);merchant.setPos(108.5,100,-2.5);
        var offer=new MerchantOffer(new ItemCost(Items.EMERALD,3),Optional.of(new ItemCost(Items.DIAMOND,1)),new ItemStack(output,2),4,0,0.0f);
        offer.addToSpecialPriceDiff(-1);check(offer.getCostA().getCount()==2,"native offer discount not applied");
        // AbstractVillager.overrideOffers is a no-op on the server; populate its actual offer list.
        var offers=merchant.getOffers();offers.clear();offers.add(offer);merchant.setTradingPlayer(buyer);
        var menu=new MerchantMenu(73,buyer.getInventory(),merchant);buyer.containerMenu=menu;
        var rows=new ArrayList<LogEntry>();var observedCosts=new ArrayList<Integer>();
        Consumer<TradeWithVillagerEvent> listener=event->{if(event.getEntity()!=buyer||event.getAbstractVillager()!=merchant)return;
            var active=event.getMerchantOffer();observedCosts.add(active.getCostA().getCount());
            var row=ItemTransactionAudit.trade(ItemTransactionAudit.party(buyer),ItemTransactionAudit.party(merchant),ItemTransactionAudit.item(active.getCostA(),level.registryAccess()),active.getCostB().isEmpty()?null:ItemTransactionAudit.item(active.getCostB(),level.registryAccess()),ItemTransactionAudit.item(active.getResult(),level.registryAccess()),active.getUses());check(row!=null,"completed native trade lacks receipt kernel");rows.add(row);
        };
        NeoForge.EVENT_BUS.addListener(listener);
        try{
            menu.getSlot(0).set(new ItemStack(Items.EMERALD,2));menu.getSlot(1).set(new ItemStack(Items.DIAMOND,1));
            check(menu.getSlot(2).getItem().getCount()==2,"native two-cost result missing");
            var before=GenericMenuSnapshot.snapshot(menu,level.registryAccess());menu.clicked(2,0,ClickType.PICKUP,buyer);
            check(offer.getUses()==1&&rows.size()==1&&menu.getCarried().is(output)&&menu.getCarried().getCount()==2&&menu.getSlot(0).getItem().isEmpty()&&menu.getSlot(1).getItem().isEmpty(),"normal trade lost exact result/consumption or duplicated event");
            check(!before.equals(GenericMenuSnapshot.snapshot(menu,level.registryAccess())),"trade slots invisible to generic snapshot");
            var row=rows.getFirst();row.materializeSnapshots();check(row.actorUuid.equals(buyer.getUUID())&&row.x==merchant.blockPosition().getX()&&row.x!=buyer.blockPosition().getX(),"trade confused buyer and seller coordinates");
            var data=NbtSerde.fromSnbt(row.beAfter);check(data.getCompound("Merchant").getUUID("UUID").equals(merchant.getUUID())&&data.getCompound("CostA").getInt("count")==2&&data.getCompound("CostB").getInt("count")==1&&observedCosts.getFirst()==2,"native receipt lost vendor, discount or second cost");
            menu.setCarried(ItemStack.EMPTY);
            // Invalid inputs must not produce result, uses or completion events.
            menu.getSlot(0).set(new ItemStack(Items.EMERALD,1));menu.getSlot(1).set(new ItemStack(Items.DIAMOND,1));menu.clicked(2,0,ClickType.PICKUP,buyer);
            check(offer.getUses()==1&&rows.size()==1&&menu.getCarried().isEmpty(),"insufficient first currency produced trade");
            menu.getSlot(0).set(new ItemStack(Items.EMERALD,2));menu.getSlot(1).set(new ItemStack(Items.STONE,1));menu.clicked(2,0,ClickType.PICKUP,buyer);
            check(offer.getUses()==1&&rows.size()==1,"wrong second currency produced trade");
            menu.getSlot(0).set(new ItemStack(Items.EMERALD,6));menu.getSlot(1).set(new ItemStack(Items.DIAMOND,3));
            menu.clicked(2,0,ClickType.QUICK_MOVE,buyer);
            int received=buyer.getInventory().items.stream().filter(s->s.is(output)).mapToInt(ItemStack::getCount).sum();
            check(offer.getUses()==4&&rows.size()==4&&received==6&&menu.getSlot(0).getItem().isEmpty()&&menu.getSlot(1).getItem().isEmpty(),"quick-move trade lost exact uses/output or duplicated completions");
            menu.getSlot(0).set(new ItemStack(Items.EMERALD,2));menu.getSlot(1).set(new ItemStack(Items.DIAMOND,1));menu.clicked(2,0,ClickType.PICKUP,buyer);
            check(offer.getUses()==4&&rows.size()==4&&menu.getCarried().isEmpty(),"exhausted offer produced a completed trade");
            check(rows.stream().mapToInt(e->e.count).sum()==8,"native trade receipt totals differ from actual four results");
            for(var receipt:rows){receipt.materializeSnapshots();var saved=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(receipt),LogEntry.class);check(saved.beAfter.equals(receipt.beAfter)&&!ItemTransactionAudit.describe(saved).isEmpty(),"native trade receipt failed journal/details");}
        }finally{NeoForge.EVENT_BUS.unregister(listener);menu.removed(buyer);buyer.containerMenu=buyer.inventoryMenu;merchant.setTradingPlayer(null);merchant.discard();}
        org.slf4j.LoggerFactory.getLogger("AvilixLogger/MerchantReceiptSmoke").info("MERCHANT_RECEIPT_NATIVE_OK: {} normal/quick-move, four exact completions/results, two currencies, discount, insufficient/wrong/exhausted refusals, merchant UUID/position, journal/details; production publication remains AuthCore gated",merchant.getType());
    }
}
