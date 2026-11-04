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
        var snapshot=ContainerSlotSnapshot.snapshotTag(level,root);
        var source=ContainerSlotSnapshot.sourceForSlot(snapshot,0);
        check(source!=null&&source.getInt("X")==root.getX()&&source.getInt("Y")==root.getY()&&source.getInt("Z")==root.getZ(),"single chest physical slot address lost");
        var legacy=snapshot.copy();legacy.remove("SlotSources");
        check(ContainerSlotSnapshot.apply(level,root,legacy),"address metadata invalidated historical rollback snapshots");
        var second=root.east(3);level.setBlockAndUpdate(second,Blocks.CHEST.defaultBlockState());
        var secondBe=(ChestBlockEntity)level.getBlockEntity(second);
        secondBe.setItem(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,4));
        var physical=new CompoundContainer(be,secondBe);
        check(physical instanceof com.roften.avilixlogger.core.CompoundContainerAccess,"compound physical accessor not transformed");
        var write=ContainerSlotSnapshot.class.getDeclaredMethod("writeContainer",Container.class,net.minecraft.core.HolderLookup.Provider.class);write.setAccessible(true);
        var previous=(net.minecraft.nbt.CompoundTag)write.invoke(null,physical,level.registryAccess());
        check(ContainerSlotSnapshot.sourceForSlot(previous,30).getInt("X")==second.getX(),"second half slot assigned to clicked block");
        secondBe.setItem(3,new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND,2));
        var current=(net.minecraft.nbt.CompoundTag)write.invoke(null,physical,level.registryAccess());
        var rows=new java.util.ArrayList<com.roften.avilixlogger.core.LogEntry>();
        com.roften.avilixlogger.core.LogStorage sink=new com.roften.avilixlogger.core.LogStorage(){
            public void append(com.roften.avilixlogger.core.LogEntry row){rows.add(row);}
            public java.util.List<com.roften.avilixlogger.core.LogEntry> query(com.roften.avilixlogger.core.LogQuery q){return java.util.List.of();}
            public java.util.List<com.roften.avilixlogger.core.LogEntry> queryReverse(com.roften.avilixlogger.core.LogQuery q){return java.util.List.of();}
            public void shutdown(){}
        };
        var emit=com.roften.avilixlogger.core.LoggerEventHandlers.class.getDeclaredMethod("emitContainerChanges",net.minecraft.nbt.CompoundTag.class,net.minecraft.nbt.CompoundTag.class,net.minecraft.core.HolderLookup.Provider.class,
            com.roften.avilixlogger.core.LogStorage.class,String.class,BlockPos.class,java.util.UUID.class,String.class,String.class,String.class,long.class);emit.setAccessible(true);
        emit.invoke(null,previous,current,level.registryAccess(),sink,level.dimension().location().toString(),root,player.getUUID(),"MenuStorageActor","player:menu",null,123L);
        check(rows.size()==1&&rows.getFirst().count==2&&rows.getFirst().type==com.roften.avilixlogger.core.ActionType.CONTAINER_TAKE,"partial take lost exact delta");
        check(rows.getFirst().x==second.getX()&&rows.getFirst().y==second.getY()&&rows.getFirst().z==second.getZ(),"partial take lost physical block address");
        var details=com.roften.avilixlogger.core.ModAuditDetails.describe(rows.getFirst());
        check(details.stream().anyMatch(line->line.getString().contains("4 → 2")),"slot before/after not rendered in GUI details");
        check(details.stream().anyMatch(line->line.getString().contains("minecraft:chest")),"physical block ID not rendered in GUI details");
        level.removeBlock(second,false);
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
