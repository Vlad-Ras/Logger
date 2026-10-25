package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.compat.chipped.ChippedCraftAudit;
import com.roften.avilixlogger.core.*;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import java.util.*;

/** Exact native Chipped workbench and the detached payload kernel; production AuthCore is not bypassed. */
@EventBusSubscriber(modid="avilixlogger")
public final class ChippedNativeSmoke {
    @SubscribeEvent public static void started(ServerStartedEvent event) {
        if(!Boolean.getBoolean("avilixlogger.chippedSmoke"))return;
        try { run(event.getServer().overworld()); }
        catch(Throwable failure) { failure.printStackTrace();System.out.println("CHIPPED_NATIVE_FAILED"); }
        finally { event.getServer().halt(false); }
    }
    private static void run(ServerLevel level)throws Exception {
        var type=Class.forName("earth.terrarium.chipped.common.menus.WorkbenchMenu");
        check(Arrays.stream(type.getDeclaredMethods()).anyMatch(m->m.getName().contains("avilixlogger$craft")),"verified optional wrapper did not apply");
        var player=new ServerPlayer(level.getServer(),level,new com.mojang.authlib.GameProfile(UUID.randomUUID(),"ChippedActor"),ClientInformation.createDefault());
        for(boolean all:List.of(false,true)) {
            var inv=player.getInventory();inv.clearContent();
            inv.setItem(9,new ItemStack(Items.OAK_PLANKS,1024));inv.setItem(10,new ItemStack(Items.OAK_PLANKS,7));
            inv.setItem(11,new ItemStack(Items.DIAMOND,3));
            var menu=(AbstractContainerMenu)type.getConstructor(int.class,Inventory.class).newInstance(41,inv);player.containerMenu=menu;
            check(GenericMenuSnapshot.snapshot(menu,level.registryAccess())==null,"workbench unexpectedly has external slots");
            type.getMethod("selectStack",int.class).invoke(menu,0);
            @SuppressWarnings("unchecked") var results=(List<ItemStack>)type.getMethod("results").invoke(menu);
            var output=results.stream().filter(s->!s.is(Items.OAK_PLANKS)).findFirst().orElseThrow(()->new AssertionError("native Chipped recipes unavailable")).copyWithCount(1);
            var before=EntityContainerSerde.snapshot(inv,level.registryAccess());
            type.getMethod("craft",ItemStack.class,boolean.class).invoke(menu,output,all);
            long produced=0;for(int i=0;i<inv.getContainerSize();i++)if(ItemStack.isSameItemSameComponents(inv.getItem(i),output))produced+=inv.getItem(i).getCount();
            check(produced==(all?1031:1024)&&inv.getItem(11).getCount()==3,"native replaceAll or expanded count changed");
            var after=EntityContainerSerde.snapshot(inv,level.registryAccess());
            var row=ChippedCraftAudit.capture(player,before,after,output,produced,all);
            check(row!=null&&row.type==ActionType.ITEM_TRANSFORM&&player.getUUID().equals(row.actorUuid)&&row.count==produced,"conversion payload lost actor/count/type");
            var preview=RollbackEngine.previewPrepared(new RollbackEngine.PreparedRollback(List.of(),List.of(),List.of(row),0));
            check(preview.applied==0&&preview.skippedReasons.getOrDefault("not_rollbackable",0)==1,"conversion entered partial craft rollback");
            inv.clearContent();row.materializeSnapshots();
            check(NbtSerde.fromSnbt(row.playerInvBefore).getList("Items",10).size()==3&&NbtSerde.fromSnbt(row.playerInvAfter).getList("Items",10).size()==3,"inventory proof was not detached");
            check(ChippedCraftAudit.capture(player,before,before,output,1,all)==null&&ChippedCraftAudit.capture(player,before,after,output,0,all)==null,"no-op/refused conversion emitted");
        }
        int mask=CaptureTypePolicy.mask();try {
            CaptureTypePolicy.install(CaptureTypePolicy.changed(mask,4,false));
            check(ChippedCraftAudit.begin(null,ItemStack.EMPTY,false)==null,"disabled capture touched player");
            check(!CaptureTypePolicy.allows(ActionType.ITEM_TRANSFORM,"mod:chipped:workbench",null),"conversion bypassed items mask");
        }finally{CaptureTypePolicy.install(mask);}
        System.out.println("CHIPPED_NATIVE_OK: actual workbench, optional wrapper, 1024-count selected/all conversions, unchanged resources, detached inventory proof, no-op and capture mask; no AuthCore bypass");
    }
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
