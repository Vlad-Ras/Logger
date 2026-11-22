package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.*;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Native inventory/menu/file operations around the production parser, balance and scene. No authorization grant. */
final class PlayerTransferNativeSmoke {
    private static final BlockPos POS=new BlockPos(96,196,112);
    private static long ids=System.nanoTime()&Long.MAX_VALUE;
    private record Exchange(LogEntry row,RollbackEngine.ReplayCheckpoint before,RollbackEngine.ReplayCheckpoint after) {}
    static void process(ServerLevel level,String mode) throws Exception {
        level.getChunk(POS);for(var side:net.minecraft.core.Direction.values())level.getChunk(POS.relative(side));
        var root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var probe=root.resolve("player-transfer-probe.json");
        if(mode.equals("player-verify")) {
            var document=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(probe)).getAsJsonObject();
            var row=GzipJson.GSON.fromJson(document.get("row"),LogEntry.class);
            var playerFile=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(row.actorUuid+".dat");
            check(fileHash(playerFile).equals(document.get("playerHash").getAsString()),"startup compensation changed offline player data");
            check(level.getBlockState(POS).is(Blocks.BARREL)&&((Container)level.getBlockEntity(POS)).isEmpty(),"player process left block inventory unrolled back");
            var history=new History(List.of(row));var prepared=prepare(level,history,row);var scene=scene(level,prepared,history);drain(scene);
            if(document.get("committed").getAsBoolean()) {
                check(scene.aborted()&&scene.failure().contains("already_committed"),"new JVM reissued a committed player transfer");
                System.out.println("PLAYER_COMMIT_RESTART_OK: durable transfer receipt refuses reissue in a new JVM after block removal; offline player file unchanged");
            } else {
                check(scene.ready(),"compensated player transfer could not be retried: "+scene.failure());scene.commit();
                check(((Container)level.getBlockEntity(POS)).getItem(0).getCount()==7,"retried player transfer restored the wrong items");
                check(fileHash(playerFile).equals(document.get("playerHash").getAsString()),"retry wrote player data");
                System.out.println("PLAYER_COMPENSATION_RESTART_OK: startup restores exact block before-image; saved player file unchanged; retry restores one exact transfer");
            }
            level.getServer().halt(false);return;
        }
        boolean committed=mode.equals("player-commit-crash");
        if(!committed&&!mode.equals("player-crash"))throw new IllegalStateException("Unknown player process: "+mode);
        var player=player(level,"RecoveryPlayerCrash");
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        @SuppressWarnings("unchecked") var online=(Map<UUID,ServerPlayer>)field.get(level.getServer().getPlayerList());
        online.put(player.getUUID(),player);var exchange=put(level,player);save(level,player);online.remove(player.getUUID());restore(level,exchange.before());
        var history=new History(List.of(exchange.row()));var prepared=prepare(level,history,exchange.row());var scene=scene(level,prepared,history);
        if(committed){drain(scene);check(scene.ready(),scene.failure());scene.commit();restore(level,exchange.before());}
        else advance(scene,RecoveryScene.Phase.VERIFY);
        RecoveryCheckpoint.flush(level.getServer());
        var playerFile=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(player.getUUID()+".dat");
        var document=new com.google.gson.JsonObject();document.add("row",GzipJson.GSON.toJsonTree(exchange.row()));document.addProperty("playerHash",fileHash(playerFile));document.addProperty("committed",committed);
        java.nio.file.Files.writeString(probe,document.toString());
        System.out.println(committed?"PLAYER_COMMIT_FORCED_CRASH_READY":"PLAYER_TRANSFER_FORCED_CRASH_READY");System.out.flush();Runtime.getRuntime().halt(73);
    }
    private static String fileHash(java.nio.file.Path file) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));}
    static void run(ServerLevel level) throws Exception {
        level.getChunk(POS);for(var side:net.minecraft.core.Direction.values())level.getChunk(POS.relative(side));
        var original=checkpoint(level);
        var player=player(level,"RecoveryTransfer");
        var field=net.minecraft.server.players.PlayerList.class.getDeclaredField("playersByUUID");field.setAccessible(true);
        @SuppressWarnings("unchecked") var online=(Map<UUID,ServerPlayer>)field.get(level.getServer().getPlayerList());
        online.put(player.getUUID(),player);
        RecoveryScene scene=null;
        try {
            var exchange=put(level,player);var history=new History(List.of(exchange.row()));restore(level,exchange.before());
            var prepared=prepare(level,history,exchange.row());
            check(prepared.playerTransfers().size()==1,"engine omitted the player transfer");
            scene=scene(level,prepared,history).preflightOnly();drain(scene);check(scene.ready(),"live dependency preview failed: "+scene.failure());scene.completePreflight();
            check(((Container)level.getBlockEntity(POS)).isEmpty()&&player.getInventory().isEmpty(),"preview gave items to either participant");
            scene=scene(level,prepared,history);drain(scene);check(scene.ready(),"proven player PUT refused: "+scene.failure());scene.commit();
            check(((Container)level.getBlockEntity(POS)).getItem(0).getCount()==7&&player.getInventory().isEmpty(),"player PUT changed player inventory or duplicated items");
            restore(level,exchange.before());scene=scene(level,prepared,history);drain(scene);
            check(scene.aborted()&&scene.failure().contains("already_committed")&&((Container)level.getBlockEntity(POS)).isEmpty(),"committed player transfer minted resources twice");

            exchange=put(level,player);restore(level,exchange.before());
            var later=exchange.row().copyForQueue();later.id=++ids;later.ts++;later.type=ActionType.BLOCK_BREAK;later.playerInvBefore=null;later.playerInvAfter=null;
            history=new History(List.of(exchange.row(),later));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);drain(scene);
            check(scene.aborted()&&scene.failure().contains("later_history")&&!scene.changedWorld(),"later block use did not invalidate old player proof");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.RESOURCE_RECHECK);
            long captureMask=CaptureTypePolicy.mask();
            try {CaptureTypePolicy.install(CaptureTypePolicy.changed(captureMask,2,false));CaptureTypePolicy.install(captureMask);}
            finally {CaptureTypePolicy.install(captureMask);}
            drain(scene);check(scene.aborted()&&scene.failure().contains("coverage_changed")&&!scene.changedWorld(),"type off/on during preflight escaped fence");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.DATA);
            var handlers=CaptureHandlers.disabled();
            try {CaptureHandlers.install(Set.of(CaptureHandlers.at(0).key()));CaptureHandlers.install(handlers);}
            finally {CaptureHandlers.install(handlers);}
            drain(scene);check(scene.aborted()&&scene.failure().contains("coverage_changed")&&((Container)level.getBlockEntity(POS)).isEmpty(),"handler off/on after WAL did not compensate");
            System.out.println("PLAYER_COVERAGE_NATIVE_OK: type off/on before mutations refused; handler off/on after WAL compensates block and releases reservations");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            player.getInventory().setItem(0,new ItemStack(Items.EMERALD,1));
            scene=scene(level,prepared,history);drain(scene);
            check(scene.aborted()&&scene.failure().contains("inventory_conflict")&&player.getInventory().getItem(0).is(Items.EMERALD),"conflicting inventory was overwritten");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.WRITE_JOURNAL);
            boolean previous=CartAuditContext.restoring(false);
            try {
                player.getInventory().setItem(0,new ItemStack(Items.EMERALD,1));
                check(player.getInventory().isEmpty(),"native player inventory reservation was bypassed");
                check(CartRestoreLocks.menuTouchesLocked(player.containerMenu),"reserved player menu was not protected");
            } finally {CartAuditContext.restoring(previous);}
            scene.cancel("native player cancellation");drain(scene);
            check(scene.aborted()&&!CartAuditContext.rollbackActive&&player.getInventory().isEmpty(),"cancel retained a player reservation or changed inventory");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            save(level,player);online.remove(player.getUUID());
            scene=scene(level,prepared,history);drain(scene);check(scene.ready(),"offline native saved inventory refused: "+scene.failure());scene.commit();
            check(((Container)level.getBlockEntity(POS)).getItem(0).getCount()==7,"offline dependency did not restore its block");
            online.put(player.getUUID(),player);

            exchange=exchange(level,player,false);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            save(level,player);online.remove(player.getUUID());
            scene=scene(level,prepared,history);drain(scene);check(scene.ready(),"offline TAKE dependency refused: "+scene.failure());scene.commit();
            check(((Container)level.getBlockEntity(POS)).isEmpty()&&player.getInventory().countItem(Items.DIAMOND)==7,"offline TAKE duplicated the receiver's items");
            online.put(player.getUUID(),player);

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            save(level,player);online.remove(player.getUUID());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.RESOURCE_RECHECK);
            online.put(player.getUUID(),player); // Login is allowed to load its native data before its first tick is frozen.
            check(CartRestoreLocks.playerLocked(player),"newly online participant was not frozen");
            drain(scene);check(scene.aborted()&&scene.failure().contains("connection_changed")&&!scene.changedWorld(),"offline-to-online change was accepted without a new preview");

            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.DATA);
            // Deliberately bypass the native setter like an unsupported mod. The final check must still catch it.
            player.getInventory().items.set(0,new ItemStack(Items.EMERALD,1));
            drain(scene);check(scene.aborted()&&scene.failure().contains("inventory_conflict")&&((Container)level.getBlockEntity(POS)).isEmpty(),"final player check failed to compensate block data");
            check(player.getInventory().getItem(0).is(Items.EMERALD),"compensation overwrote an external player change");
            var memory=memoryScene(level,player);scene=memory.scene();drain(scene);check(scene.ready(),"player memory scene refused: "+scene.failure());scene.commit();
            for(int i=0;i<8&&memory.payload().get()!=null;i++){System.gc();Thread.sleep(20);}
            check(memory.payload().get()==null,"completed scene retained detached player snapshot data");
            System.out.println("PLAYER_TRANSFER_MEMORY_RELEASE_OK: detached player item NBT with 1 MiB custom data becomes collectable after commit");
            profileSnapshots(player);
            exchange=put(level,player);restore(level,exchange.before());history=new History(List.of(exchange.row()));prepared=prepare(level,history,exchange.row());
            scene=scene(level,prepared,history);advance(scene,RecoveryScene.Phase.WRITE_JOURNAL);
            level.getServer().getPlayerList().remove(player);drain(scene);
            check(scene.aborted()&&scene.failure().contains("connection_changed")&&!scene.changedWorld(),"native logout was accepted after resource preflight");
            System.out.println("PLAYER_TRANSFER_NATIVE_OK: real shift-click producer snapshots; player unchanged; preview; exact block repair; durable repeat refusal; later history; conflicts; reservations/cancel; native offline player data; login change and final compensation");
        } finally {
            if(scene!=null&&!scene.aborted()&&scene.phase()!=RecoveryScene.Phase.CLOSED){scene.cancel("native cleanup");drain(scene);}
            online.remove(player.getUUID());restore(level,original);check(!CartAuditContext.rollbackActive,"player fixture leaked locks");
        }
    }
    private record MemoryScene(RecoveryScene scene,java.lang.ref.WeakReference<CompoundTag> payload) {}
    private static MemoryScene memoryScene(ServerLevel level,ServerPlayer player) {
        var exchange=exchange(level,player,true,1024*1024);restore(level,exchange.before());var history=new History(List.of(exchange.row()));
        var prepared=prepare(level,history,exchange.row());
        var payload=prepared.playerTransfers().getFirst().before().getCompound("Inventory").getList("Items",Tag.TAG_COMPOUND)
                .getCompound(0).getCompound("components").getCompound("minecraft:custom_data");
        check(payload.contains("Probe",Tag.TAG_BYTE_ARRAY)&&payload.getByteArray("Probe").length==1024*1024,"memory probe not present in player snapshot");
        return new MemoryScene(scene(level,prepared,history),new java.lang.ref.WeakReference<>(payload));
    }
    private static ServerPlayer player(ServerLevel level,String name) {
        var profile=new com.mojang.authlib.GameProfile(UUID.randomUUID(),name);
        var player=new ServerPlayer(level.getServer(),level,profile,ClientInformation.createDefault());
        player.connection=new net.minecraft.server.network.ServerGamePacketListenerImpl(level.getServer(),
                new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND),player,
                net.minecraft.server.network.CommonListenerCookie.createInitial(profile,false)) {
            @Override public boolean isAcceptingMessages(){return true;}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet){}
            @Override public void send(net.minecraft.network.protocol.Packet<?> packet,net.minecraft.network.PacketSendListener callback){}
        };return player;
    }
    private static void profileSnapshots(ServerPlayer player) {
        player.getInventory().clearContent();
        for(int i=0;i<41;i++)player.getInventory().setItem(i,new ItemStack(Items.STONE,64));
        var data=new CompoundTag();data.putByteArray("Probe",new byte[4096]);
        player.getInventory().getItem(0).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(data));
        for(int i=0;i<50;i++)PlayerBlockTransfer.snapshot(player,player.containerMenu);
        var bean=java.lang.management.ManagementFactory.getThreadMXBean();
        var allocator=bean instanceof com.sun.management.ThreadMXBean value&&value.isThreadAllocatedMemorySupported()?value:null;
        if(allocator!=null&&!allocator.isThreadAllocatedMemoryEnabled())allocator.setThreadAllocatedMemoryEnabled(true);
        long thread=Thread.currentThread().threadId(),bytes=allocator==null?0:allocator.getThreadAllocatedBytes(thread),start=System.nanoTime();
        for(int i=0;i<200;i++){PlayerBlockTransfer.snapshot(player,player.containerMenu);PlayerBlockTransfer.snapshot(player,player.containerMenu);}
        long elapsed=System.nanoTime()-start,allocated=allocator==null?-1:allocator.getThreadAllocatedBytes(thread)-bytes;
        System.out.printf(Locale.ROOT,"PLAYER_SNAPSHOT_PROFILE pairs=200 slots=41 custom_data_bytes=4096 average_pair_ms=%.4f allocated_bytes_per_pair=%d scope=extra_producer_snapshots_only%n",elapsed/200e6,allocated<0?-1:allocated/200);
    }
    private static Exchange put(ServerLevel level,ServerPlayer player) {
        return exchange(level,player,true);
    }
    private static Exchange exchange(ServerLevel level,ServerPlayer player,boolean put) {
        return exchange(level,player,put,0);
    }
    private static Exchange exchange(ServerLevel level,ServerPlayer player,boolean put,int payloadBytes) {
        level.removeBlockEntity(POS);level.setBlock(POS,Blocks.BARREL.defaultBlockState(),2|16|32|64);
        var storage=(Container)level.getBlockEntity(POS);storage.clearContent();player.getInventory().clearContent();
        if(put)player.getInventory().setItem(9,new ItemStack(Items.DIAMOND,7));else storage.setItem(0,new ItemStack(Items.DIAMOND,7));
        if(payloadBytes>0){var tag=new CompoundTag();tag.putByteArray("Probe",new byte[payloadBytes]);player.getInventory().getItem(9).set(net.minecraft.core.component.DataComponents.CUSTOM_DATA,net.minecraft.world.item.component.CustomData.of(tag));}
        var menu=ChestMenu.threeRows(1,player.getInventory(),storage);player.containerMenu=menu;
        check(PlayerBlockTransfer.eligible(level,POS,menu,player),"native barrel/chest menu not eligible");
        var before=checkpoint(level);var playerBefore=PlayerBlockTransfer.snapshot(player,menu);
        menu.clicked(put?27:0,0,ClickType.QUICK_MOVE,player);
        var after=checkpoint(level);var playerAfter=PlayerBlockTransfer.snapshot(player,menu);
        check(put?storage.getItem(0).getCount()==7&&player.getInventory().isEmpty():storage.isEmpty()&&player.getInventory().countItem(Items.DIAMOND)==7,"native shift-click failed");
        var row=new LogEntry();row.id=++ids;row.ts=System.currentTimeMillis();row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;
        row.actorUuid=player.getUUID();row.actorName=player.getGameProfile().getName();row.source="player:container_click";
        row.dim=level.dimension().location().toString();row.x=POS.getX();row.y=POS.getY();row.z=POS.getZ();
        row.blockBefore=before.state().toString();row.blockAfter=after.state().toString();row.beBefore=NbtSerde.toSnbt(before.be());row.beAfter=NbtSerde.toSnbt(after.be());
        row.containerSlotsBefore=NbtSerde.toSnbt(before.slots());row.containerSlotsAfter=NbtSerde.toSnbt(after.slots());
        PlayerBlockTransfer.attach(row,new PlayerBlockTransfer.Capture(UUID.randomUUID(),player.getUUID(),row.dim,POS,menu.getClass().getName(),playerBefore,playerAfter,true,UUID.randomUUID()),before.slots(),after.slots());
        return new Exchange(row,before,after);
    }
    private static RollbackEngine.PreparedRollback prepare(ServerLevel level,History history,LogEntry row) {
        var owner=UUID.randomUUID();var scope=new RollbackPlanManager.Scope(level.dimension().location().toString(),POS,POS);
        var plan=RollbackPlanManager.save(owner,0,row.ts,null,null,List.of(scope),true);
        try{return RollbackEngine.prepareBlockReplay(history,plan,scope,()->false);}finally{RollbackPlanManager.clear(owner);}
    }
    private static RecoveryScene scene(ServerLevel level,RollbackEngine.PreparedRollback prepared,History history) {
        check(prepared.preparationSkips().isEmpty(),"prepared player scene contains skips: "+prepared.preparationSkips());
        return new RecoveryScene(prepared.blocks().stream().map(block->new RecoveryScene.Target(level,block)).toList(),List.of(),64L*1024*1024,128,
                ignored->{},prepared.playerTransfers(),proofs->PlayerBlockTransfer.verifyHistory(history,proofs));
    }
    private static void save(ServerLevel level,ServerPlayer player) throws Exception {
        var method=net.minecraft.server.players.PlayerList.class.getDeclaredMethod("save",ServerPlayer.class);method.setAccessible(true);method.invoke(level.getServer().getPlayerList(),player);
    }
    private static RollbackEngine.ReplayCheckpoint checkpoint(ServerLevel level){return new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(level.getBlockState(POS)),NbtSerde.snapshotBlockEntityAt(level,POS),ContainerSlotSnapshot.snapshotTag(level,POS));}
    private static void restore(ServerLevel level,RollbackEngine.ReplayCheckpoint value) {
        boolean previous=CartAuditContext.restoring(true);
        try {level.removeBlockEntity(POS);level.setBlock(POS,NbtSerde.readBlockState(level,value.state()),2|16|32|64);
            if(value.be()!=null)check(NbtSerde.readBlockEntity(level,POS,value.be()),"fixture NBT restore");
            if(value.slots()!=null)check(ContainerSlotSnapshot.apply(level,POS,value.slots()),"fixture slots restore");
        } finally {CartAuditContext.restoring(previous);}
    }
    private static void advance(RecoveryScene scene,RecoveryScene.Phase phase){for(int i=0;i<10000&&scene.phase()!=phase&&!scene.aborted();i++)step(scene);check(scene.phase()==phase,"missing phase "+phase+": "+scene.failure());}
    private static void drain(RecoveryScene scene){for(int i=0;i<10000&&!scene.ready()&&!scene.aborted()&&!scene.quarantined();i++)step(scene);check(scene.ready()||scene.aborted(),"player scene did not terminate: "+scene.phase()+" "+scene.failure());}
    private static void step(RecoveryScene scene){scene.step(1,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),8);if(Set.of(RecoveryScene.Phase.RESOURCE_CHECK,RecoveryScene.Phase.PLAYER_VERIFY,RecoveryScene.Phase.WRITE_JOURNAL).contains(scene.phase()))java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private record History(List<LogEntry> rows) implements LogStorage {
        public void append(LogEntry row){throw new UnsupportedOperationException();}public void shutdown(){}
        public List<LogEntry> queryReverse(LogQuery q){throw new UnsupportedOperationException();}
        public List<LogEntry> query(LogQuery q){return rows.stream().filter(row->q.dim==null||q.dim.equals("*")||q.dim.equals(row.dim))
                .filter(row->row.ts>=q.sinceTs&&row.ts<=q.untilTs).filter(row->q.exactIds==null||Arrays.stream(q.exactIds).anyMatch(id->id==row.id))
                .filter(row->q.types==null||q.types.contains(row.type)).filter(row->q.chronological?row.ts>q.cursorTs||row.ts==q.cursorTs&&row.id>q.afterId:row.id>q.afterId)
                .filter(row->q.minPos==null||row.x>=q.minPos.getX()&&row.y>=q.minPos.getY()&&row.z>=q.minPos.getZ())
                .filter(row->q.maxPos==null||row.x<=q.maxPos.getX()&&row.y<=q.maxPos.getY()&&row.z<=q.maxPos.getZ())
                .sorted(q.chronological?Comparator.comparingLong((LogEntry row)->row.ts).thenComparingLong(row->row.id):Comparator.comparingLong(row->row.id)).limit(q.limit).toList();}
    }
}
