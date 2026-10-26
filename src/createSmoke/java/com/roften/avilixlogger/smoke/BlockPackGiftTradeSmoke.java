package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.blockpack.BlockPackJoinGiftAudit;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.item.ItemTossEvent;
import java.util.*;
import java.util.function.Consumer;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Fresh test UUIDs and an isolated world; never force a licensed production capture. */
final class BlockPackGiftTradeSmoke {
    private static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger("AvilixLogger/BlockPackGiftTrade");
    static void run(ServerLevel level)throws Exception{
        var handler=Class.forName("com.blockfactory.blockpack.event.PlayerJoinHandler");var first=handler.getMethod("firstJoinGift",ServerPlayer.class);
        check(Arrays.stream(handler.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$")),"gift delivery mixin absent");
        var dataType=Class.forName("com.blockfactory.blockpack.event.GiftSavedData");var data=dataType.getMethod("get",net.minecraft.server.MinecraftServer.class).invoke(null,level.getServer());var enabled=dataType.getMethod("setGiftEnabled",boolean.class);var mark=dataType.getMethod("markReceived",UUID.class);
        var book=BuiltInRegistries.ITEM.stream().filter(i->i.getClass().getName().equals("com.blockfactory.blockpack.item.GuideBookItem")).findFirst().orElseThrow();
        var workbench=BuiltInRegistries.BLOCK.stream().filter(b->b.getClass().getName().equals("com.blockfactory.blockpack.block.WorkbenchBlock")).findFirst().orElseThrow().asItem();
        var player=ScgunsNativeSmoke.player(level,"NativeGiftRecipient");player.setPos(96.5,100,-.5);enabled.invoke(data,true);first.invoke(null,player);
        check(count(player,book)==1&&count(player,workbench)==1,"first join gift missing inventory delivery");first.invoke(null,player);
        check(count(player,book)==1&&count(player,workbench)==1&&!(boolean)mark.invoke(data,player.getUUID()),"repeat join duplicated gift or received flag absent");
        var gated=ScgunsNativeSmoke.player(level,"DisabledGiftRecipient");gated.setPos(96.5,100,-.5);enabled.invoke(data,false);first.invoke(null,gated);check(count(gated,book)==0&&count(gated,workbench)==0,"disabled gift delivered items");enabled.invoke(data,true);first.invoke(null,gated);check(count(gated,book)==1&&count(gated,workbench)==1,"disabled first join consumed eligibility");
        for(String mode:List.of("full","cancel-toss","cancel-spawn")){
            var target=ScgunsNativeSmoke.player(level,"GiftOverflow");target.setPos(96.5,100,-.5);for(int i=0;i<36;i++)target.getInventory().setItem(i,new ItemStack(Items.STONE,64));
            var attempted=new ArrayList<ItemEntity>();Consumer<EntityJoinLevelEvent> spawn=e->{if(e.getEntity() instanceof ItemEntity item&&item.position().distanceTo(target.position())<4){attempted.add(item);if(mode.equals("cancel-spawn"))e.setCanceled(true);}};
            Consumer<ItemTossEvent> toss=e->{if(e.getPlayer()==target&&mode.equals("cancel-toss"))e.setCanceled(true);};NeoForge.EVENT_BUS.addListener(spawn);NeoForge.EVENT_BUS.addListener(toss);
            try {first.invoke(null,target);check(count(target,book)==0&&count(target,workbench)==0,"full inventory accepted gift unexpectedly");long accepted=attempted.stream().filter(e->level.getEntity(e.getUUID())==e&&!e.isRemoved()).count();check(accepted==(mode.equals("full")?2:0),"gift overflow ignored actual cancellation: "+mode);first.invoke(null,target);check(attempted.size()==(mode.equals("cancel-toss")?0:2),"failed/overflow gift retried despite native received marker");
                for(var entity:attempted){var drop=new ItemTransactionAudit.Drop(entity.getUUID(),level.getEntity(entity.getUUID())==entity&&!entity.isRemoved(),ItemTransactionAudit.item(entity.getItem(),level.registryAccess()),level.dimension().location().toString(),entity.getX(),entity.getY(),entity.getZ());var row=ItemTransactionAudit.gift(ItemTransactionAudit.party(target),ItemTransactionAudit.item(entity.getItem(),level.registryAccess()),0,drop,true);check(row!=null&&row.actorUuid==null&&row.count==(drop.accepted()?1:0),"gift kernel invented recipient as giver or accepted rejected spawn");}
            }finally{NeoForge.EVENT_BUS.unregister(spawn);NeoForge.EVENT_BUS.unregister(toss);for(var entity:attempted)entity.discard();}
        }
        // Native helper's partial stack behavior, independently of firstJoin's fixed one-item gifts.
        var partial=ScgunsNativeSmoke.player(level,"PartialGiftRecipient");partial.setPos(96.5,100,-.5);for(int i=0;i<36;i++)partial.getInventory().setItem(i,new ItemStack(Items.STONE,64));partial.getInventory().setItem(0,new ItemStack(workbench,61));
        var give=handler.getDeclaredMethod("giveOrDrop",ServerPlayer.class,ItemStack.class);give.setAccessible(true);var drops=new ArrayList<ItemEntity>();Consumer<EntityJoinLevelEvent> listener=e->{if(e.getEntity() instanceof ItemEntity item&&item.getItem().is(workbench))drops.add(item);};NeoForge.EVENT_BUS.addListener(listener);
        try {give.invoke(null,partial,new ItemStack(workbench,8));check(partial.getInventory().getItem(0).getCount()==64&&drops.size()==1&&drops.getFirst().getItem().getCount()==5&&level.getEntity(drops.getFirst().getUUID())==drops.getFirst(),"partial native gift did not split 3 inserted + 5 accepted world items");}
        finally{NeoForge.EVENT_BUS.unregister(listener);for(var drop:drops)drop.discard();}
        var creative=ScgunsNativeSmoke.player(level,"CreativeGiftRecipient");creative.setPos(96.5,100,-.5);creative.getAbilities().instabuild=true;for(int i=0;i<36;i++)creative.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        long observedBefore=BlockPackJoinGiftAudit.count(creative.getInventory(),new ItemStack(workbench));var swallowed=new ItemStack(workbench,1);give.invoke(null,creative,swallowed);
        check(swallowed.isEmpty()&&BlockPackJoinGiftAudit.count(creative.getInventory(),new ItemStack(workbench))==observedBefore,"full creative native add no longer consumes without inserting");
        var creativeReceipt=ItemTransactionAudit.gift(ItemTransactionAudit.party(creative),ItemTransactionAudit.item(new ItemStack(workbench),level.registryAccess()),0,null,true);check(creativeReceipt!=null&&creativeReceipt.count==0,"creative swallowed input became confirmed inventory gift");
        check(BlockPackJoinGiftAudit.begin(player,new ItemStack(book))==null&&!BlockPackJoinGiftAudit.active(),"fixture bypassed AuthCore or retained a gift scope");
        LOG.info("BLOCKPACK_JOIN_GIFT_NATIVE_OK: first/repeat/disabled then enabled; inventory/full/partial; toss/spawn cancellation and actual received marker; 3 inserted + 5 dropped; creative swallowed stack is not insertion; no authorization bypass");
        var type=Class.forName("com.blockfactory.blockpack.entity.BfWanderingTrader");var merchant=BuiltInRegistries.ENTITY_TYPE.stream().filter(t->BuiltInRegistries.ENTITY_TYPE.getKey(t).getNamespace().equals("bf_blockpack")).map(t->t.create(level)).filter(Objects::nonNull).filter(type::isInstance).findFirst().orElseThrow();
        var trader=(AbstractVillager)merchant;check(!trader.getOffers().isEmpty(),"native BlockPack trade resource produced no offers");
        MerchantTransactionSmoke.run(level,trader,workbench);
        LOG.info("BLOCKPACK_MERCHANT_NATIVE_OK: actual BlockPack trader/resources and native transaction event contract");
    }
    private static int count(ServerPlayer player,Item item){return player.getInventory().items.stream().filter(s->s.is(item)).mapToInt(ItemStack::getCount).sum();}
}
