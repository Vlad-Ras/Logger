package com.roften.avilixlogger.core;

import com.roften.avilixlogger.mixin.RecoveryEntityLevelAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.*;
import net.minecraft.nbt.*;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import java.util.*;
import java.util.concurrent.*;

/** Entity participant in the same block-scene transaction and the same durable undo record. */
public final class RecoveryEntityBatch {
    public record Target(ServerLevel level,EntityReplayPlan.Prepared snapshot) {}
    public record Chunk(ServerLevel level,ChunkPos pos) {}
    private record Indexed(EntitySavedIndex.Result index,Set<Long> receipts) {}
    private record Saved(Target target,CompoundTag before,CompoundTag after,boolean already,boolean receipt) {}
    private MinecraftServer server;
    private final List<Target> targets;
    private final List<Saved> saved=new ArrayList<>();
    private final Set<UUID> wanted=new HashSet<>();
    private final EntitySavedIndex.Progress progress=new EntitySavedIndex.Progress();
    private java.util.function.Consumer<List<EntityReplayPlan.Prepared>> history;
    private CompletableFuture<Indexed> indexing;
    private Indexed indexed;
    private volatile boolean cancelled;
    private boolean ownsReservation,changed;
    private long backupBytes;

    public RecoveryEntityBatch(List<Target> targets,java.util.function.Consumer<List<EntityReplayPlan.Prepared>> history) {
        this.targets=new ArrayList<>(targets);this.history=history;
        server=targets.getFirst().level().getServer();
        var roots=new HashSet<UUID>();var events=new HashSet<Long>();
        for(var target:targets) {
            var value=target.snapshot();
            if(!roots.add(value.uuid()))throw new IllegalStateException("Повторный корень сущности: "+value.uuid());
            for(var id:value.tree().ids())if(!wanted.add(id))throw new IllegalStateException("Пересечение деревьев сущностей: "+id);
            for(long event:value.eventIds())if(event<=0||!events.add(event))throw new IllegalStateException("Повторный/неверный ID события сущности: "+event);
        }
        if(wanted.size()>4096)throw new IllegalStateException("Слишком много UUID в одной сцене: "+wanted.size());
    }
    public int size(){return targets.size();}
    public MinecraftServer server(){return server;}
    public int scanned(){return progress.scanned.get();}
    public int scanTotal(){return progress.total.get();}
    public long backupBytes(){return backupBytes;}
    public boolean changed(){return changed;}
    public void begin(int maxChunks) {
        ownsReservation=true;RecoveryEntityReservations.begin(server,wanted,maxChunks);RecoveryEntityReservations.verify();
        // Freeze referenced live UUIDs before the save barrier. Unloaded references cannot move;
        // newly loaded references are retained by the join/unload hooks until the scene ends.
        RecoveryCheckpoint.flush(server);
        var sources=EntitySavedIndex.sources(server);var folder=RecoveryJournal.directory(server);
        var prepared=targets.stream().map(Target::snapshot).toList();
        var verifier=history;history=null;
        indexing=RecoveryJournal.io(()->{
            var receipts=RecoveryJournal.readCommitted(folder);var active=new ArrayList<EntityReplayPlan.Prepared>();
            var needed=new HashSet<UUID>();
            for(var value:prepared) {
                long count=value.eventIds().stream().filter(receipts::contains).count();
                if(count>0&&count<value.eventIds().size())throw new IllegalStateException("entity_partial_receipt_chain: "+value.uuid()+"; сузьте период после завершённой операции");
                if(count==0){active.add(value);needed.addAll(value.tree().ids());}
            }
            if(cancelled)throw new CancellationException();
            if(!active.isEmpty())verifier.accept(List.copyOf(active));
            var index=needed.isEmpty()?new EntitySavedIndex.Result(Map.of()):EntitySavedIndex.scan(sources,needed,progress,()->cancelled);
            return new Indexed(index,receipts);
        });
    }
    public boolean indexed() {
        RecoveryEntityReservations.verify();
        if(indexed!=null)return true;
        if(!indexing.isDone())return false;
        indexed=indexing.join();return true;
    }
    public List<Chunk> chunks() {
        if(indexed==null)throw new IllegalStateException("Проверка сохранённых UUID ещё не закончена");
        var result=new LinkedHashSet<Chunk>();
        for(var target:targets) {
            if(target.snapshot().eventIds().stream().allMatch(indexed.receipts()::contains))continue;
            for(var node:target.snapshot().tree().nodes())result.add(new Chunk(target.level(),new ChunkPos(node.pos())));
            for(var id:target.snapshot().tree().ids()) {
                var locations=indexed.index().locations(id);
                if(locations.size()>1)throw new IllegalStateException("entity_uuid_saved_collision: "+id+" "+locations);
                for(var location:locations) {
                    if(!target.snapshot().tree().ids().contains(location.root()))throw new IllegalStateException("entity_attached_outside_scene: "+id);
                    var level=level(server,location.dimension());result.add(new Chunk(level,location.chunk()));
                }
                var live=RecoveryEntityReservations.live(id);
                if(live!=null)result.add(new Chunk((ServerLevel)live.level(),new ChunkPos(live.blockPosition())));
            }
        }
        return List.copyOf(result);
    }
    /** Called after full chunk readiness and vanilla's pending entity load. No mutation yet. */
    public boolean capture(int index) {
        RecoveryEntityReservations.verify();
        var target=targets.get(index);var value=target.snapshot();
        boolean receipt=value.eventIds().stream().allMatch(indexed.receipts()::contains);
        if(receipt){saved.add(new Saved(target,null,null,true,true));return true;}
        for(var chunk:chunksFor(target))if(!((RecoveryEntityLevelAccessor)chunk.level()).avilixlogger$entityManager().areEntitiesLoaded(chunk.pos().toLong()))return false;
        var current=RecoveryEntityReservations.live(value.uuid());
        if(current!=null&&current.getVehicle()!=null)throw problem(target,"entity_attached_outside_scene");
        if(current==null) {
            for(var id:value.tree().ids())if(RecoveryEntityReservations.live(id)!=null)throw problem(target,"entity_incomplete_live_tree");
            for(var id:value.tree().ids())if(!indexed.index().locations(id).isEmpty())throw problem(target,"entity_saved_not_loaded");
        } else checkTree(target,current);
        CompoundTag before=current==null?null:NbtSerde.snapshotEntity((ServerLevel)current.level(),current);
        if(current!=null&&before==null)throw problem(target,"entity_live_backup_failed");
        backupBytes+=192+PayloadSizeEstimator.estimateTags(before);
        if(value.after()!=null&&before!=null) {
            // A present lifecycle UUID is preserved, never rewound over later equipment/state.
            if(value.before()!=null&&!same(before,value.after()))throw problem(target,"entity_replacement_requires_state_graph");
            saved.add(new Saved(target,before,before,true,false));return true;
        }
        if(value.after()==null&&before==null){saved.add(new Saved(target,null,null,true,false));return true;}
        if(current!=null&&current.level()!=target.level())throw problem(target,"entity_world_dimension_conflict");
        if(value.policyIssue()!=null)throw problem(target,value.policyIssue());
        // Create registers even an empty vanilla minecart in a separate controller registry.
        // Its structural participant owns that graph; generic lifecycle NBT cannot compensate it.
        if(value.type().equals("minecraft:minecart")&&net.neoforged.fml.ModList.get()!=null&&net.neoforged.fml.ModList.get().isLoaded("create"))
            throw problem(target,"replay_linked_create_entity");
        checkScoreboard(target.level(),value.tree().ids());
        if(before!=null&&!same(before,value.before()))throw problem(target,"entity_world_conflict");
        String invalid=NbtSerde.validateEntitySnapshot(value.type(),value.uuid(),value.after()!=null?value.after():value.before());
        if(invalid!=null)throw problem(target,invalid);
        saved.add(new Saved(target,before,value.after(),false,false));
        return true;
    }
    private List<Chunk> chunksFor(Target target) {
        var result=new LinkedHashSet<Chunk>();
        for(var id:target.snapshot().tree().ids())for(var saved:indexed.index().locations(id))result.add(new Chunk(level(server,saved.dimension()),saved.chunk()));
        for(var node:target.snapshot().tree().nodes())result.add(new Chunk(target.level(),new ChunkPos(node.pos())));
        return List.copyOf(result);
    }
    public void recheck(int index) {
        var value=saved.get(index);if(!value.receipt()) {
            var current=RecoveryEntityReservations.live(value.target().snapshot().uuid());
            if(value.before()==null?current!=null:current==null||!same(NbtSerde.snapshotEntity((ServerLevel)current.level(),current),value.before()))
                throw problem(value.target(),"entity_changed_after_capture");
        }
    }
    public List<RecoveryJournal.EntityImage> images() {
        return saved.stream().filter(value->!value.already()).map(value->new RecoveryJournal.EntityImage(value.target().level().dimension().location().toString(),
                value.target().snapshot().uuid(),value.target().snapshot().type(),value.before(),value.after(),value.target().snapshot().eventIds())).toList();
    }
    public List<Long> newEvents() {
        var result=new LinkedHashSet<Long>();for(var value:saved)if(!value.receipt())result.addAll(value.target().snapshot().eventIds());return List.copyOf(result);
    }
    public void apply(int index) {
        var value=saved.get(index);if(value.already())return;
        var target=value.target();var current=RecoveryEntityReservations.live(target.snapshot().uuid());
        if(value.before()==null?current!=null:current==null||!same(NbtSerde.snapshotEntity((ServerLevel)current.level(),current),value.before()))
            throw problem(target,"entity_changed_before_apply");
        changed=true;
        checkScoreboard(target.level(),target.snapshot().tree().ids());
        replace(target.level(),target.snapshot().uuid(),target.snapshot().type(),value.before(),value.after());
    }
    public void verify(int index) {
        RecoveryEntityReservations.verify();
        var value=saved.get(index);if(value.receipt())return;
        var current=RecoveryEntityReservations.live(value.target().snapshot().uuid());
        if(value.after()==null?current!=null:current==null||!same(NbtSerde.snapshotEntity((ServerLevel)current.level(),current),value.after()))
            throw problem(value.target(),"entity_after_verification_failed");
    }
    public void undo(int index) {
        var value=saved.get(index);if(value.already())return;
        var existing=RecoveryEntityReservations.live(value.target().snapshot().uuid());
        if(value.before()==null?existing==null:existing!=null&&same(NbtSerde.snapshotEntity((ServerLevel)existing.level(),existing),value.before()))return;
        replace(value.target().level(),value.target().snapshot().uuid(),value.target().snapshot().type(),value.after(),value.before());
        var current=RecoveryEntityReservations.live(value.target().snapshot().uuid());
        if(value.before()==null?current!=null:current==null||!same(NbtSerde.snapshotEntity((ServerLevel)current.level(),current),value.before()))
            throw problem(value.target(),"entity_compensation_verification_failed");
    }
    public void report(RollbackReport report) {
        for(var value:saved) {
            if(value.already())report.onSkipped(value.receipt()?"entity_recovery_receipt_completed":value.after()==null?"entity_already_absent":"entity_already_present");
            else {
                report.onApplied(value.target().snapshot().action());
                if(value.after()==null)report.entitiesRemoved+=value.target().snapshot().tree().nodes().size();else report.entitiesRespawned+=value.target().snapshot().tree().nodes().size();
            }
        }
    }
    public void cancel(){cancelled=true;}
    public void release(){cancelled=true;if(ownsReservation)RecoveryEntityReservations.release();saved.clear();targets.clear();wanted.clear();indexed=null;indexing=null;history=null;server=null;}
    private static void checkScoreboard(ServerLevel level,Set<UUID> ids) {
        var board=level.getScoreboard();
        for(var id:ids) {
            var holder=net.minecraft.world.scores.ScoreHolder.forNameOnly(id.toString());
            if(board.getPlayersTeam(id.toString())!=null||board.getObjectives().stream().anyMatch(objective->board.getPlayerScoreInfo(holder,objective)!=null))
                throw new IllegalStateException("entity_scoreboard_graph_required: "+id);
        }
    }
    private void checkTree(Target target,Entity root) {
        var value=target.snapshot();var expected=value.tree();var current=root.getSelfAndPassengers().toList();
        if(current.size()!=expected.nodes().size())throw problem(target,"entity_live_tree_mismatch");
        var types=new HashMap<UUID,String>();for(var node:expected.nodes())types.put(node.uuid(),node.type());
        for(var entity:current)if(!Objects.equals(types.get(entity.getUUID()),BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))throw problem(target,"entity_uuid_type_or_tree_conflict");
    }
    private static boolean same(CompoundTag current,CompoundTag expected){return Objects.equals(current,expected);}
    private static IllegalStateException problem(Target target,String reason){return new IllegalStateException(reason+": "+target.level().dimension().location()+" "+target.snapshot().pos()+" UUID="+target.snapshot().uuid());}
    static ServerLevel level(MinecraftServer server,String dimension) {
        var id=ResourceLocation.tryParse(dimension);var level=id==null?null:server.getLevel(ResourceKey.create(Registries.DIMENSION,id));
        if(level==null)throw new IllegalStateException("Измерение сущности недоступно: "+dimension);return level;
    }
    private static void replace(ServerLevel level,UUID root,String type,CompoundTag currentImage,CompoundTag desired) {
        var nodes=new LinkedHashMap<UUID,String>();
        for(var tag:Arrays.asList(currentImage,desired))if(tag!=null)for(var node:EntityRecoverySnapshot.require(tag,type,root).nodes())nodes.put(node.uuid(),node.type());
        checkScoreboard(level,nodes.keySet());
        var actual=new ArrayList<Entity>();
        for(var id:nodes.keySet()) {
            var entity=RecoveryEntityReservations.live(id);
            if(entity==null)continue;
            if(entity.level()!=level)throw new IllegalStateException("entity_compensation_dimension_conflict: "+id);
            if(!nodes.get(id).equals(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString()))throw new IllegalStateException("entity_compensation_type_conflict: "+id);
            if(entity.getVehicle()!=null&&!nodes.containsKey(entity.getVehicle().getUUID()))throw new IllegalStateException("entity_compensation_foreign_vehicle: "+id);
            for(var passenger:entity.getPassengers())if(!nodes.containsKey(passenger.getUUID()))throw new IllegalStateException("entity_compensation_foreign_passenger: "+id);
            actual.add(entity);
        }
        boolean restoring=CartAuditContext.restoring(true);
        try {
            for(int i=actual.size()-1;i>=0;i--)actual.get(i).discard();
            if(desired!=null) {
                var result=NbtSerde.restoreEntityFromSnapshot(level,type,root,desired);
                if(!result.success())throw new IllegalStateException(result.reason()+": "+root);
            }
        } finally {CartAuditContext.restoring(restoring);}
    }
    /** Bootstrap undo is exclusive; vanilla's entity inbox is pumped without running game ticks. */
    static void undoOnStartup(MinecraftServer server,List<RecoveryJournal.EntityImage> images) throws Exception {
        var wanted=new HashSet<UUID>();
        for(var image:images)for(var tag:Arrays.asList(image.before(),image.after()))if(tag!=null)wanted.addAll(EntityRecoverySnapshot.require(tag,image.type(),image.uuid()).ids());
        RecoveryEntityReservations.begin(server,wanted);
        var sources=EntitySavedIndex.sources(server);
        var indexed=RecoveryJournal.io(()->EntitySavedIndex.scan(sources,wanted,new EntitySavedIndex.Progress(),()->false)).join();
        var chunks=new LinkedHashSet<Chunk>();
        for(var image:images)for(var tag:Arrays.asList(image.before(),image.after()))if(tag!=null)for(var node:EntityRecoverySnapshot.require(tag,image.type(),image.uuid()).nodes())chunks.add(new Chunk(level(server,image.dimension()),new ChunkPos(node.pos())));
        for(var id:wanted) {
            var locations=indexed.locations(id);
            if(locations.size()>1)throw new IllegalStateException("entity_uuid_saved_collision_during_compensation: "+id);
            for(var location:locations) {
                if(!wanted.contains(location.root()))throw new IllegalStateException("entity_foreign_tree_during_compensation: "+id);
                chunks.add(new Chunk(level(server,location.dimension()),location.chunk()));
            }
        }
        for(var chunk:chunks){RecoveryEntityReservations.hold(chunk.level(),chunk.pos());chunk.level().getChunk(chunk.pos().x,chunk.pos().z);}
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        server.managedBlock(()->{
            for(var chunk:chunks)((RecoveryEntityLevelAccessor)chunk.level()).avilixlogger$entityManager().tick();
            if(System.nanoTime()>deadline)throw new IllegalStateException("entity_compensation_chunk_load_timeout");
            return chunks.stream().allMatch(chunk->((RecoveryEntityLevelAccessor)chunk.level()).avilixlogger$entityManager().areEntitiesLoaded(chunk.pos().toLong()));
        });
        for(var id:wanted)if(!indexed.locations(id).isEmpty()&&RecoveryEntityReservations.live(id)==null)throw new IllegalStateException("entity_compensation_saved_not_loaded: "+id);
        for(var image:images) {
            replace(level(server,image.dimension()),image.uuid(),image.type(),image.after(),image.before());
            var actual=RecoveryEntityReservations.live(image.uuid());
            if(image.before()==null?actual!=null:actual==null||!same(NbtSerde.snapshotEntity((ServerLevel)actual.level(),actual),image.before()))
                throw new IllegalStateException("entity_compensation_startup_verification_failed: "+image.uuid());
        }
    }
}
