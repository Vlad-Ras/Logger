package com.roften.avilixlogger.smoke;

import com.mrcrayfish.backpacked.core.*;
import com.mrcrayfish.backpacked.common.augment.Augments;
import com.mrcrayfish.backpacked.common.augment.impl.FunnellingAugment;
import com.mrcrayfish.backpacked.common.backpack.UnlockableSlots;
import com.mrcrayfish.backpacked.inventory.BackpackInventory;
import com.mrcrayfish.backpacked.blockentity.*;
import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.compat.backpacked.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.*;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.nio.file.*;
import java.util.*;

/** Development-only native compatibility checks. AuthCore stays enforced; never shipped. */
@EventBusSubscriber(modid="avilixlogger")
public final class BackpackNativeSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event){
        if(!Boolean.getBoolean("avilixlogger.backpackSmoke"))return;
        try{
            var world=event.getServer().overworld();
            if(!Boolean.getBoolean("avilixlogger.backpackAbsent")){
                check(BackpackSupport.available(),"supported release not detected");
                var player=net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(world);
                var index=new BackpackIndex();ItemStack bag=new ItemStack(ModItems.BACKPACK.get());
                bag.set(ModDataComponents.UNLOCKABLE_SLOTS.get(),UnlockableSlots.ALL);
                var record=index.identify(bag,player.getUUID(),"smoke_owner");UUID id=record.id;String shortId=record.shortId;
                check(shortId.length()<=16&&id.equals(index.resolve(shortId.toLowerCase(Locale.ROOT))),"short lookup");
                var equipped=com.mrcrayfish.backpacked.BackpackHelper.getBackpacks(player);equipped.set(0,bag);
                var inventory=new BackpackInventory(0,9,3,player,bag);
                check(BackpackAudit.ref(inventory).holder()==player,"native inventory accessor");
                Class.forName("com.mrcrayfish.backpacked.network.play.ServerPlayHandler");
                Class.forName("net.minecraft.world.inventory.CraftingMenu");
                Class.forName("net.minecraft.world.inventory.ResultSlot");
                inventory.setItem(0,new ItemStack(Items.DIAMOND,12));inventory.setItem(4,new ItemStack(Items.IRON_INGOT,7));
                check(inventory.removeItem(0,5).getCount()==5,"native remove");
                check(inventory.addItem(new ItemStack(Items.DIAMOND,3)).isEmpty(),"native auto insert");
                inventory.saveItemsToStack();check(inventory.getItem(0).getCount()==10,"native content count");
                check(BackpackAudit.copySlots(inventory)[4].getCount()==7,"raw native slot capture");
                equipped.set(0,ItemStack.EMPTY);check(BackpackAudit.ref(inventory)==null,"obsolete equipment inventory remained live");equipped.set(0,bag);
                var oldSlots=BackpackAudit.copySlots(inventory);inventory.addItem(new ItemStack(Items.DIAMOND,3));
                var delta=BackpackSlotDelta.capture(oldSlots,BackpackAudit.copySlots(inventory),world.registryAccess());
                check(delta!=null&&delta.type()==ActionType.BACKPACK_PUT,"native insertion audit type");
                var guiDiff=ContainerSlotDiffUtil.diffAggregated(delta.before().toString(),delta.after().toString(),world.registryAccess());
                check(guiDiff.size()==1&&guiDiff.values().iterator().next()==3,"GUI item delta");
                check(BackpackSlotDelta.capture(BackpackAudit.copySlots(inventory),BackpackAudit.copySlots(inventory),world.registryAccess())==null,"unchanged slots produced log");
                inventory.removeItem(0,3);inventory.saveItemsToStack();

                var before=Augments.get(bag);var upgraded=before.setAugment(Augments.Position.FIRST,FunnellingAugment.TYPE.defaultSupplier().get()).setState(Augments.Position.FIRST,false);Augments.set(bag,upgraded);
                index.snapshot(record,bag,world.registryAccess());
                var decoded=BackpackRecovery.decode(world,record.snapshot);
                check(id.equals(BackpackIds.id(decoded))&&shortId.equals(BackpackIds.shortId(decoded)),"snapshot identity");
                check(Augments.get(decoded).equals(upgraded),"augment settings/state round trip");
                var restored=new BackpackInventory(0,9,3,player,decoded);
                check(restored.getItem(0).getCount()==10&&restored.getItem(4).getCount()==7,"restored contents");
                var saved=index.save(new CompoundTag(),world.registryAccess());var reload=BackpackIndex.load(saved);
                check(reload.resolve(shortId).equals(id)&&reload.owned("SMOKE_OWNER").size()==1,"restart owner/short lookup");
                var another=new ItemStack(ModItems.BACKPACK.get());var audit=BackpackIds.metadata(bag);audit.putUUID("Id",UUID.randomUUID());BackpackIds.write(another,audit);
                check(!reload.identify(another,null,null).shortId.equals(shortId),"short ID collision");
                CompoundTag bad=record.snapshot.copy();bad.putString("id","missingmod:missing_backpack");
                boolean refused=false;try{BackpackRecovery.decode(world,bad);}catch(Exception expected){refused=true;}check(refused,"missing registry item accepted");
                BlockPos dockPos=new BlockPos(0,128,4);world.setBlock(dockPos,ModBlocks.BACKPACK_DOCK.get().defaultBlockState(),3);
                var dock=(BackpackDockBlockEntity)world.getBlockEntity(dockPos);ItemStack placed=decoded.copy();check(dock!=null&&dock.setBackpackOrPop(placed)&&placed.isEmpty(),"native dock placement");
                check(id.equals(BackpackIds.id(dock.getBackpackWithContents())),"dock identity");
                var shelf=new ShelfBlockEntity(new BlockPos(2,128,4),ModBlocks.OAK_BACKPACK_SHELF.get().defaultBlockState());shelf.setLevel(world);shelf.setBackpack(dock.getBackpackWithContents());
                check(id.equals(BackpackIds.id(shelf.getBackpack())),"shelf identity");
                var shelfInventory=new ShelfBlockEntity.BackpackShelfContainer(shelf,27);
                check(BackpackAudit.ref(shelfInventory)!=null,"native shelf stack accessor");
                shelf.setBackpack(ItemStack.EMPTY);
                check(BackpackAudit.ref(shelfInventory)==null,"obsolete shelf inventory remained live");
                world.removeBlock(dockPos,false);
                Path dir=Files.createTempDirectory("backpack-claim-smoke");Path claim=dir.resolve(id+".nbt");
                try{BackpackRecovery.reserve(claim,record.snapshot);refused=false;try{BackpackRecovery.reserve(claim,record.snapshot);}catch(java.io.IOException expected){refused=true;}check(refused,"duplicate reservation accepted");}
                finally{Files.deleteIfExists(claim);Files.deleteIfExists(dir);}
                LogQuery q=new LogQuery();q.backpackId=id;check(q.copy().backpackId.equals(id),"exact query identity lost");
                check(BackpackIds.fromSource(BackpackIds.prefix(id)+"test").equals(id),"source identity");
                check(BackpackIds.fromSource(BackpackIds.prefix(id).replace(":","_")+"test")==null,"non-exact prefix accepted");
                System.out.println("BACKPACK_NATIVE_SMOKE_OK: native contents, equipment accessor, dock/shelf identity, augments, restart index, collisions, strict snapshots, exclusive recovery reservations");
            }else{
                check(!BackpackSupport.available(),"unexpected Backpacked dependency");
                System.out.println("BACKPACK_ABSENT_SMOKE_OK: Logger starts without Backpacked/Framework");
            }
            event.getServer().halt(false);
        }catch(Throwable e){e.printStackTrace();event.getServer().halt(false);throw new IllegalStateException("Backpack smoke failed",e);}
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
