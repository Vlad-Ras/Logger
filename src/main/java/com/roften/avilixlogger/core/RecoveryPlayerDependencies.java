package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only external participant: only block states change; player contents are never overwritten. */
final class RecoveryPlayerDependencies {
    record Frontier(String dimension,BlockPos pos,int currentIndex,int finalIndex) {}
    private record Address(String dimension,BlockPos pos) {}
    private record Bound(UUID id,ServerPlayer online,CompoundTag expected) {}
    record Check(Path directory,Path receipts,Map<UUID,CompoundTag> offline,List<PlayerBlockTransfer.Proof> history,
                 List<PlayerBlockTransfer.Proof> pending,List<PlayerBlockTransfer.Proof> all,boolean initial,
                 java.util.concurrent.atomic.AtomicReference<List<Long>> events,java.util.function.Consumer<List<PlayerBlockTransfer.Proof>> verifier,int dataVersion) {
        void verify(long memoryLimit,BooleanSupplier cancelled) throws IOException {
            if(initial)try {
                var committed=RecoveryJournal.readCommitted(receipts);
                for(var proof:pending)if(committed.contains(proof.event()))throw new IllegalStateException("replay_player_transfer_already_committed: #"+proof.event());
                events.set(all.stream().map(PlayerBlockTransfer.Proof::event).distinct().filter(id->!committed.contains(id)).toList());
            } catch(IOException problem){throw problem;}catch(RuntimeException problem){throw problem;}catch(Exception problem){throw new IOException(problem);}
            if(!pending.isEmpty()||!initial)verifier.accept(all);
            var previous=new HashMap<UUID,CompoundTag>();var transfers=new HashSet<UUID>();
            for(var proof:history) {
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                if(!transfers.add(proof.transfer()))throw new IllegalStateException("replay_player_transfer_duplicate: "+proof.transfer());
                var old=previous.put(proof.player(),proof.after());
                if(old!=null&&!old.equals(proof.before()))throw new IllegalStateException("replay_player_history_gap: "+proof.player()+"; запись #"+proof.event());
            }
            for(var entry:offline.entrySet()) {
                if(cancelled.getAsBoolean())throw new java.util.concurrent.CancellationException();
                var actual=readOffline(directory,entry.getKey(),memoryLimit,dataVersion);
                if(!actual.equals(entry.getValue()))throw new IllegalStateException("replay_player_inventory_conflict: "+entry.getKey()+"; сохранённый инвентарь отличается от конца переноса");
            }
        }
        List<RecoveryItemBalance.Change> changes(long limit) {
            long bytes=0;var result=new ArrayList<RecoveryItemBalance.Change>();
            for(var proof:pending) {
                bytes=Math.addExact(bytes,192+PayloadSizeEstimator.estimateTags(proof.before(),proof.after()));
                if(bytes>limit)throw new IllegalStateException("Связанные снимки игроков превышают оставшийся лимит памяти");
                result.add(new RecoveryItemBalance.Change(PlayerBlockTransfer.counted(proof.before()),PlayerBlockTransfer.counted(proof.after())));
            }
            return List.copyOf(result);
        }
    }
    private MinecraftServer server;
    private List<PlayerBlockTransfer.Proof> supplied;
    private final List<Bound> bound=new ArrayList<>();
    private List<PlayerBlockTransfer.Proof> history=List.of();
    private List<PlayerBlockTransfer.Proof> pending=List.of();
    private boolean began;
    private final long coverageGeneration=CaptureCoverage.generation();
    private final java.util.function.Consumer<List<PlayerBlockTransfer.Proof>> verifier;
    private final java.util.concurrent.atomic.AtomicReference<List<Long>> events=new java.util.concurrent.atomic.AtomicReference<>(List.of());
    RecoveryPlayerDependencies(MinecraftServer server,List<PlayerBlockTransfer.Proof> supplied,java.util.function.Consumer<List<PlayerBlockTransfer.Proof>> verifier) {
        this.server=server;this.supplied=List.copyOf(supplied);this.verifier=verifier;
    }
    void begin(List<Frontier> frontiers) {
        if(began)throw new IllegalStateException("Player dependency check already started");began=true;
        if(ServerCaptureGuard.paused())throw new IllegalStateException("replay_player_transfer_capture_gap: запись событий сейчас приостановлена");
        var positions=new HashMap<Address,Frontier>();for(var frontier:frontiers)positions.put(new Address(frontier.dimension(),frontier.pos()),frontier);
        var unique=new HashMap<Long,PlayerBlockTransfer.Proof>();
        for(var proof:supplied) {
            var old=unique.putIfAbsent(proof.event(),proof);
            if(old!=null&&!old.equals(proof))throw new IllegalStateException("replay_player_transfer_duplicate: #"+proof.event());
        }
        var ordered=new ArrayList<>(unique.values());ordered.sort(Comparator.comparingLong(PlayerBlockTransfer.Proof::timestamp).thenComparingLong(PlayerBlockTransfer.Proof::event));
        var active=new HashSet<UUID>();var pending=new ArrayList<PlayerBlockTransfer.Proof>();
        for(var proof:ordered) {
            var frontier=positions.get(new Address(proof.dimension(),proof.pos()));
            if(frontier==null||proof.afterIndex()>frontier.finalIndex())throw new IllegalStateException("replay_player_transfer_outside_scene: #"+proof.event());
            if(frontier.currentIndex()<0)throw new IllegalStateException("replay_player_transfer_checkpoint_missing: #"+proof.event());
            if(proof.afterIndex()>frontier.currentIndex()){active.add(proof.player());pending.add(proof);}
        }
        if(active.size()>128)throw new IllegalStateException("Слишком много связанных игроков: "+active.size());
        history=ordered.stream().filter(proof->active.contains(proof.player())).toList();
        var latest=new LinkedHashMap<UUID,CompoundTag>();for(var proof:history)latest.put(proof.player(),proof.after());
        this.pending=List.copyOf(pending);
        for(var entry:latest.entrySet()) {
            if(!entry.getValue().getCompound("Cursor").isEmpty())throw new IllegalStateException("replay_player_cursor_boundary_required: "+entry.getKey()+"; выберите конец периода с пустым курсором");
            CartRestoreLocks.playerId(entry.getKey());
            var player=server.getPlayerList().getPlayer(entry.getKey());
            if(player!=null) {
                CartRestoreLocks.player(player);CartRestoreLocks.container(player.getInventory());
                if(!PlayerBlockTransfer.snapshot(player,player.containerMenu).equals(entry.getValue()))throw conflict(entry.getKey());
            }
            bound.add(new Bound(entry.getKey(),player,entry.getValue()));
        }
    }
    boolean active(){return !bound.isEmpty();}
    Check check(boolean includeHistory) {
        recheckLive();var offline=new HashMap<UUID,CompoundTag>();
        for(var value:bound)if(value.online()==null)offline.put(value.id(),value.expected());
        return new Check(server.getWorldPath(LevelResource.PLAYER_DATA_DIR),RecoveryJournal.directory(server),Map.copyOf(offline),
                includeHistory?history:List.of(),includeHistory?pending:List.of(),supplied,includeHistory,events,verifier,
                net.minecraft.SharedConstants.getCurrentVersion().getDataVersion().getVersion());
    }
    List<Long> newEvents(){return events.get();}
    void recheckLive() {
        CaptureCoverage.checkHealthy();
        if(coverageGeneration!=CaptureCoverage.generation())throw new IllegalStateException("replay_player_capture_coverage_changed: запись переключалась во время проверки");
        for(var value:bound) {
            var player=server.getPlayerList().getPlayer(value.id());
            if(player!=value.online())throw new IllegalStateException("replay_player_connection_changed: "+value.id()+"; повторите после входа/выхода игрока");
            if(player!=null&&!PlayerBlockTransfer.snapshot(player,player.containerMenu).equals(value.expected()))throw conflict(value.id());
        }
    }
    void release(){bound.clear();history=List.of();pending=List.of();supplied=List.of();server=null;}
    private static IllegalStateException conflict(UUID id) {
        return new IllegalStateException("replay_player_inventory_conflict: "+id+"; инвентарь/курсор отличается от конца переноса; игрок не изменён");
    }
    static CompoundTag readOffline(Path directory,UUID player,long limit,int dataVersion) throws IOException {
        Path file=directory.resolve(player+".dat");
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)||Files.isSymbolicLink(file))
            throw new IOException("replay_player_data_missing: "+player+"; нужен сохранённый инвентарь или вход игрока");
        long bytes=Files.size(file),budget=Math.min(64L*1024*1024,limit);
        if(bytes<=0||bytes>budget)throw new IOException("replay_player_data_size: "+player);
        var root=NbtIo.readCompressed(file,NbtAccounter.create(budget));
        if(root==null||!root.contains("DataVersion",Tag.TAG_INT)||root.getInt("DataVersion")!=dataVersion)
            throw new IOException("replay_player_data_version: "+player+"; сначала нужен штатный вход игрока для обновления данных");
        if(root==null||!root.hasUUID("UUID")||!player.equals(root.getUUID("UUID"))
                ||!(root.get("Inventory") instanceof ListTag items)||!items.isEmpty()&&items.getElementType()!=Tag.TAG_COMPOUND)
            throw new IOException("replay_player_data_invalid: "+player);
        var inventory=new CompoundTag();inventory.putInt("Size",41);var slots=new TreeMap<Integer,CompoundTag>();
        for(var raw:items) {
            var item=(CompoundTag)raw;
            if(!item.contains("Slot",Tag.TAG_BYTE))throw new IOException("replay_player_data_invalid_slot: "+player);
            int slot=item.getByte("Slot")&255;
            slot=slot<36?slot:slot>=100&&slot<104?slot-64:slot==150?40:-1;
            if(slot<0)throw new IOException("replay_player_data_invalid_slot: "+player);
            var copy=item.copy();copy.putInt("Slot",slot);
            if(slots.putIfAbsent(slot,copy)!=null)throw new IOException("replay_player_data_duplicate_slot: "+player);
        }
        var sorted=new ListTag();sorted.addAll(slots.values());inventory.put("Items",sorted);
        var state=new CompoundTag();state.put("Inventory",inventory);state.put("Cursor",new CompoundTag());return state;
    }
}
