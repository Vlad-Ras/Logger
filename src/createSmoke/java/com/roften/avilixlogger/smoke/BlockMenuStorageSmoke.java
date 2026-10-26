package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.ContainerSlotSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.*;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import static com.roften.avilixlogger.smoke.ScgunsNativeSmoke.check;

/** Vanilla regression for a menu's concrete block storage, without optional mod classes. */
public final class BlockMenuStorageSmoke {
    public static void run(ServerLevel level) throws Exception {
        var player=ScgunsNativeSmoke.player(level,"MenuStorageActor");
        var root=new BlockPos(44,100,44);var clicked=root.above(3);
        level.setBlockAndUpdate(root,Blocks.CHEST.defaultBlockState());
        var be=(ChestBlockEntity)level.getBlockEntity(root);
        var menu=(ChestMenu)be.createMenu(95,player.getInventory(),player);
        check(root.equals(ContainerSlotSnapshot.menuStoragePos(level,menu,clicked)),"native chest backing locator lost");
        check(root.equals(ContainerSlotSnapshot.menuStoragePos(level,menu,root)),"single chest locator changed");
        var generic=ChestMenu.oneRow(96,player.getInventory());
        check(clicked.equals(ContainerSlotSnapshot.menuStoragePos(level,generic,clicked)),"generic locator changed");
        var combined=ChestMenu.sixRows(97,player.getInventory(),new CompoundContainer(be,new SimpleContainer(27)));
        check(clicked.equals(ContainerSlotSnapshot.menuStoragePos(level,combined,clicked)),"compound locator changed");
        var detached=new ChestBlockEntity(root,be.getBlockState());detached.setLevel(level);
        var stale=new ChestMenu(net.minecraft.world.inventory.MenuType.GENERIC_9x1,98,player.getInventory(),detached,1);
        check(ContainerSlotSnapshot.menuStoragePos(level,stale,clicked)==null,"replaced BE accepted as storage");
        detached.setLevel(level.getServer().getLevel(net.minecraft.world.level.Level.NETHER));
        check(ContainerSlotSnapshot.menuStoragePos(level,stale,clicked)==null,"foreign-world BE accepted as storage");
        var accepted=new java.util.concurrent.atomic.AtomicBoolean(true);
        var worker=new Thread(()->accepted.set(ContainerSlotSnapshot.menuStoragePos(level,menu,clicked)!=null),"menu-storage-off-thread");
        worker.start();worker.join(3000);check(!worker.isAlive()&&!accepted.get(),"storage world access escaped server thread");
        detached.setLevel(level);menu.removed(player);generic.removed(player);combined.removed(player);stale.removed(player);level.removeBlock(root,false);
        org.slf4j.LoggerFactory.getLogger("AvilixLogger/MenuStorageSmoke").info("BLOCK_MENU_STORAGE_NATIVE_OK: concrete vanilla backing, generic/compound fallback, replaced/foreign-world/off-thread refusal");
    }
}
