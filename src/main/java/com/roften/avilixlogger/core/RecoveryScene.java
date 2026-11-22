package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

import java.util.*;
import java.util.concurrent.TimeUnit;

/** One server-thread transaction across every block batch. Preparation never changes the world. */
public final class RecoveryScene {
    public enum Phase { RESERVE, ENTITY_INDEX, WAIT_CHUNKS, CAPTURE, ENTITY_CAPTURE, RECHECK, ENTITY_RECHECK, RESOURCE_CHECK, RESOURCE_RECHECK, WRITE_JOURNAL, STATES, DATA, ENTITY_APPLY, VERIFY, ENTITY_VERIFY, PLAYER_VERIFY, READY, UNDO_ENTITIES, UNDO_STATES, UNDO_DATA, ABORTED, CLOSED, QUARANTINED }
    public record Target(ServerLevel level, RollbackEngine.PreparedBlockSnapshot snapshot) {}
    private record Address(ServerLevel level, BlockPos pos) {}
    private record ChunkAddress(ServerLevel level, long chunk) {}
    private record FluidShape(Address root,int width,int height) {}
    private record Saved(Target target, RollbackEngine.ReplayCheckpoint before, BlockState desired, boolean already) {}

    private final ArrayList<Target> targets;
    private final ArrayList<Saved> saved = new ArrayList<>();
    private final Map<Address, BlockState> desired = new HashMap<>();
    private final Set<Address> fluidDependencies=new HashSet<>();
    private final ArrayList<ChunkAddress> chunks;
    private final long memoryLimit;
    private final int maxChunks;
    private final RecoveryEntityBatch entities;
    private final RecoveryPlayerDependencies players;
    private final RollbackReport report = new RollbackReport();
    private Phase phase = Phase.RESERVE;
    private int cursor, changed, chunksRequested;
    private long memoryBytes, chunkDeadline, journalDeadline;
    private String failure = "";
    private boolean ownsLocks;
    private RecoveryJournal.Handle journal;
    private boolean cancelledWhileWriting;
    private boolean preflightOnly;
    private java.util.concurrent.CompletableFuture<Void> resourceCheck;
    private final java.util.concurrent.atomic.AtomicBoolean resourcesCancelled=new java.util.concurrent.atomic.AtomicBoolean();
    private long resourceDeadline;

    public RecoveryScene(List<Target> supplied, long memoryLimit, int maxChunks) {this(supplied,List.of(),memoryLimit,maxChunks,ignored->{});}
    public RecoveryScene(List<Target> supplied,List<RecoveryEntityBatch.Target> entityTargets,long memoryLimit,int maxChunks,
                         java.util.function.Consumer<List<EntityReplayPlan.Prepared>> history) {
        this(supplied,entityTargets,memoryLimit,maxChunks,history,List.of(),ignored->{});
    }
    public RecoveryScene(List<Target> supplied,List<RecoveryEntityBatch.Target> entityTargets,long memoryLimit,int maxChunks,
                         java.util.function.Consumer<List<EntityReplayPlan.Prepared>> history,List<PlayerBlockTransfer.Proof> playerTransfers,
                         java.util.function.Consumer<List<PlayerBlockTransfer.Proof>> playerHistory) {
        this.entities=entityTargets.isEmpty()?null:new RecoveryEntityBatch(entityTargets,history);
        this.players=playerTransfers.isEmpty()?null:new RecoveryPlayerDependencies(supplied.getFirst().level().getServer(),playerTransfers,playerHistory);
        this.maxChunks=maxChunks;
        targets = new ArrayList<>(supplied.size());
        var unique = new HashMap<Address, RollbackEngine.PreparedBlockSnapshot>();
        var uniqueChunks = new LinkedHashSet<ChunkAddress>();
        for (Target target : supplied) {
            var address = new Address(target.level(), target.snapshot().pos());
            var old = unique.putIfAbsent(address, target.snapshot());
            if (old != null) {
                if (!old.equals(target.snapshot())) throw new IllegalStateException("Противоречивые снимки одной позиции: " + address);
                continue;
            }
            targets.add(target);
            desired.put(address,null); // Complete membership is known before resolving shared capabilities.
            uniqueChunks.add(new ChunkAddress(target.level(), new ChunkPos(target.snapshot().pos()).toLong()));
            // Neighbours are locked too; retain boundary chunks used by shape/storage checks.
            for (Direction side : Direction.values()) uniqueChunks.add(new ChunkAddress(target.level(), new ChunkPos(target.snapshot().pos().relative(side)).toLong()));
        }
        if (uniqueChunks.size() > maxChunks) throw new IllegalStateException("Для целого восстановления требуется " + uniqueChunks.size()
                + " чанков; crashRecovery.maxLoadedChunks=" + maxChunks + ". Мир не изменён.");
        chunks = new ArrayList<>(uniqueChunks);
        this.memoryLimit = Math.max(0, memoryLimit);
    }

    public Phase phase() { return phase; }
    /** Include unchanged multiblock parts within the administrator's selected scopes, without inventing historical NBT. */
    public RecoveryScene includeFluidDependencies(List<RollbackPlanManager.Scope> scopes) {
        if(phase!=Phase.RESERVE||ownsLocks)throw new IllegalStateException("Зависимости нужно подготовить до запуска сцены");
        if(!CreateSupport.available())return this;
        var limits=com.roften.avilixlogger.compat.create.CreateFluidRecovery.limits();
        var shapes=new HashSet<FluidShape>();var allChunks=new LinkedHashSet<>(chunks);
        for(var target:List.copyOf(targets)) {
            var checkpoints=new ArrayList<>(target.snapshot().checkpoints());
            checkpoints.add(new RollbackEngine.ReplayCheckpoint(target.snapshot().stateTag(),target.snapshot().beTag(),target.snapshot().slotsTag()));
            for(var checkpoint:checkpoints) {
                var be=checkpoint.be();if(be==null||!be.getString("id").equals("create:fluid_tank")||be.contains("Controller"))continue;
                int width=be.getInt("Size"),height=be.getInt("Height");
                if(!be.contains("Size",net.minecraft.nbt.Tag.TAG_INT)||!be.contains("Height",net.minecraft.nbt.Tag.TAG_INT)
                        ||width<1||width>3||height<1||height>limits.maxHeight())throw new IllegalStateException("replay_fluid_invalid_dimensions: "+target.snapshot().pos());
                if((long)target.snapshot().pos().getX()+width-1>Integer.MAX_VALUE||(long)target.snapshot().pos().getZ()+width-1>Integer.MAX_VALUE
                        ||(long)target.snapshot().pos().getY()+height-1>Integer.MAX_VALUE)throw new IllegalStateException("replay_fluid_invalid_dimensions: "+target.snapshot().pos());
                var shape=new FluidShape(new Address(target.level(),target.snapshot().pos()),width,height);if(!shapes.add(shape))continue;
                for(int x=0;x<width;x++)for(int z=0;z<width;z++)for(int y=0;y<height;y++) {
                    var pos=target.snapshot().pos().offset(x,y,z);var address=new Address(target.level(),pos);if(desired.containsKey(address))continue;
                    String dimension=target.level().dimension().location().toString();
                    boolean selected=scopes.stream().anyMatch(scope->scope.dimension().equals(dimension)
                            &&pos.getX()>=scope.min().getX()&&pos.getY()>=scope.min().getY()&&pos.getZ()>=scope.min().getZ()
                            &&pos.getX()<=scope.max().getX()&&pos.getY()<=scope.max().getY()&&pos.getZ()<=scope.max().getZ());
                    if(!selected||target.level().isOutsideBuildHeight(pos))throw new IllegalStateException("replay_fluid_multiblock_outside_scope: "+dimension+" "+pos+"; выберите всю ёмкость");
                    if((long)(targets.size()+1)*320>memoryLimit)throw new IllegalStateException("Зависимости баков превышают оставшийся лимит памяти");
                    desired.put(address,null);fluidDependencies.add(address);
                    targets.add(new Target(target.level(),new RollbackEngine.PreparedBlockSnapshot(pos,null,null,null,ActionType.BLOCK_ENTITY_NBT_CHANGE)));
                    allChunks.add(new ChunkAddress(target.level(),new ChunkPos(pos).toLong()));
                    for(var side:Direction.values())allChunks.add(new ChunkAddress(target.level(),new ChunkPos(pos.relative(side)).toLong()));
                    if(allChunks.size()>maxChunks)throw new IllegalStateException("Полные ёмкости превышают crashRecovery.maxLoadedChunks="+maxChunks);
                }
            }
        }
        chunks.clear();chunks.addAll(allChunks);return this;
    }
    /** Runs the normal world/UUID checks but stops before creating a journal or mutating objects. */
    public RecoveryScene preflightOnly() {
        if (phase != Phase.RESERVE || ownsLocks) throw new IllegalStateException("Проверка уже началась");
        preflightOnly = true;
        return this;
    }
    public boolean ready() { return phase == Phase.READY; }
    public boolean aborted() { return phase == Phase.ABORTED; }
    public boolean quarantined() { return phase == Phase.QUARANTINED; }
    public String failure() { return failure; }
    public RollbackReport report() { return report; }
    public int progress() { return phase==Phase.ENTITY_INDEX?entities.scanned():phase==Phase.WAIT_CHUNKS?chunksRequested+cursor:cursor; }
    public int progressTotal() {return phase==Phase.ENTITY_INDEX?entities.scanTotal():entityPhase()?entities.size():phase==Phase.RESOURCE_RECHECK?targets.size()+(entities==null?0:entities.size()):phase==Phase.WAIT_CHUNKS?chunks.size()*2:targets.size();}
    private boolean entityPhase(){return phase==Phase.ENTITY_CAPTURE||phase==Phase.ENTITY_RECHECK||phase==Phase.ENTITY_APPLY||phase==Phase.ENTITY_VERIFY||phase==Phase.UNDO_ENTITIES;}
    public int size() { return targets.size()+(entities==null?0:entities.size()); }
    public boolean changedWorld() { return changed > 0||entities!=null&&entities.changed(); }

    public void cancel(String reason) {
        if (phase == Phase.CLOSED || phase == Phase.ABORTED || phase == Phase.QUARANTINED) return;
        failure = reason;
        resourcesCancelled.set(true);
        if(entities!=null)entities.cancel();
        if(journal!=null&&!journal.done()){cancelledWhileWriting=true;return;}
        cursor = 0;
        phase = changedWorld() ? entities==null?Phase.UNDO_STATES:Phase.UNDO_ENTITIES : Phase.ABORTED;
        if (phase == Phase.ABORTED) {
            try {finishAbort();}
            catch(Throwable problem) {phase=Phase.QUARANTINED;failure+="; аварийный журнал не удалось безопасно закрыть; блокировки сохранены: "+problem;throw new IllegalStateException(failure,problem);}
        }
    }

    /** Each call is bounded; chunk timeouts use elapsed time, not the availability of idle ticks. */
    public void step(int operations, long deadline, int chunkRequests) {
        if (phase == Phase.CLOSED || phase == Phase.ABORTED || phase == Phase.QUARANTINED || phase == Phase.READY) return;
        if (!ownsLocks) {
            if (CartAuditContext.rollbackActive) throw new IllegalStateException("Другая операция уже зарезервировала мир");
            CartAuditContext.rollbackActive = true;
            ownsLocks = true;
        }
        int used = 0, requested = 0;
        try {
            while (used < operations && System.nanoTime() < deadline) {
                if (phase == Phase.RESERVE) {
                    if (cursor == targets.size()) {
                        cursor=0;
                    if(entities!=null){entities.begin(maxChunks);phase=Phase.ENTITY_INDEX;return;}
                        phase=Phase.WAIT_CHUNKS;chunkDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);continue;
                    }
                    var target = targets.get(cursor++);
                    var pos = target.snapshot().pos();
                    CartRestoreLocks.block(target.level(), pos);
                    for (Direction side : Direction.values()) CartRestoreLocks.block(target.level(), pos.relative(side));
                } else if (phase == Phase.ENTITY_INDEX) {
                    if(!entities.indexed())return;
                    var all=new LinkedHashSet<ChunkAddress>(chunks);
                    for(var chunk:entities.chunks())all.add(new ChunkAddress(chunk.level(),chunk.pos().toLong()));
                    if(all.size()>maxChunks)throw new IllegalStateException("Связанная сцена блоков/сущностей превышает лимит чанков: "+all.size()+" > "+maxChunks);
                    chunks.clear();chunks.addAll(all);phase=Phase.WAIT_CHUNKS;cursor=0;chunkDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
                } else if (phase == Phase.WAIT_CHUNKS) {
                    if (chunksRequested < chunks.size()) {
                        var chunk = chunks.get(chunksRequested);
                        var chunkPos = new ChunkPos(chunk.chunk());
                        boolean loaded = chunk.level().getChunkSource().getChunkNow(chunkPos.x,chunkPos.z)!=null;
                        if (!loaded && chunkRequests==0) throw new IllegalStateException("Загрузка чанков отключена: "+chunkPos+"; мир не изменён");
                        if (!loaded && requested >= chunkRequests) return;
                        chunksRequested++;
                        if(entities!=null)RecoveryEntityReservations.hold(chunk.level(),new ChunkPos(chunk.chunk()));
                        else CartRestoreLocks.keepLoaded(chunk.level(), new ChunkPos(chunk.chunk()).getWorldPosition());
                        if (!loaded) requested++;
                    } else {
                        if (cursor == chunks.size()) { cursor=0; phase=Phase.CAPTURE; continue; }
                        var chunk=chunks.get(cursor);
                        var pos=new ChunkPos(chunk.chunk());
                        if (chunk.level().getChunkSource().getChunkNow(pos.x,pos.z)==null) {
                            if (System.nanoTime() >= chunkDeadline) throw new IllegalStateException("Чанк не готов: " + chunk.level().dimension().location() + " " + pos + "; мир не изменён");
                            return;
                        }
                        cursor++;
                    }
                } else if (phase == Phase.CAPTURE) {
                    if (cursor == targets.size()) { cursor=0; phase=entities==null?Phase.RECHECK:Phase.ENTITY_CAPTURE; continue; }
                    capture(targets.get(cursor++));
                } else if (phase == Phase.ENTITY_CAPTURE) {
                    if(cursor==entities.size()){cursor=0;phase=Phase.RECHECK;continue;}
                    if(!entities.capture(cursor)){
                        if(System.nanoTime()>chunkDeadline)throw new IllegalStateException("entity_chunk_load_timeout; мир не изменён");
                        return;
                    }
                    cursor++;
                    if(memoryBytes+entities.backupBytes()>memoryLimit)throw new IllegalStateException("Резервные снимки блоков и сущностей превышают лимит памяти; мир не изменён");
                } else if (phase == Phase.RECHECK) {
                    if (cursor == saved.size()) {
                        cursor=0;
                        if(entities!=null)phase=Phase.ENTITY_RECHECK;else startJournal();
                        continue;
                    }
                    var value=saved.get(cursor++);
                    verifyCurrent(value, value.before());
                    verifyLinkedBlocks(value);
                } else if (phase == Phase.ENTITY_RECHECK) {
                    if(cursor==entities.size()){cursor=0;startJournal();continue;}
                    entities.recheck(cursor++);
                } else if (phase == Phase.RESOURCE_CHECK) {
                    if(!resourceCheck.isDone()) {
                        if(System.nanoTime()>=resourceDeadline)throw new IllegalStateException("Проверка ресурсов не завершилась за 30 секунд; мир не изменён");
                        return;
                    }
                    resourceCheck.join();resourceCheck=null;cursor=0;phase=Phase.RESOURCE_RECHECK;
                } else if (phase == Phase.RESOURCE_RECHECK) {
                    if(cursor<saved.size()){var value=saved.get(cursor++);verifyCurrent(value,value.before());}
                    else if(entities!=null&&cursor-saved.size()<entities.size())entities.recheck(cursor++-saved.size());
                    else {if(players!=null)players.recheckLive();cursor=0;writeJournal();}
                } else if (phase == Phase.WRITE_JOURNAL) {
                    if(!journal.done()) {
                        if(System.nanoTime()>=journalDeadline){
                            phase=Phase.QUARANTINED;
                            failure="Запись аварийного журнала не завершилась за 2 минуты. Мир не изменён; область защищена до устранения ошибки диска/перезапуска.";
                            throw new IllegalStateException(failure);
                        }
                        return;
                    }
                    journal.await();
                    if(cancelledWhileWriting){cancel(failure);return;}
                    if(players!=null)players.recheckLive();
                    phase=Phase.STATES;
                } else if (phase == Phase.STATES) {
                    if (cursor == saved.size()) { cursor=0; phase=Phase.DATA; continue; }
                    var value=saved.get(cursor++);
                    if (!value.already()) { changed++; writeState(value.target(), value.desired()); }
                } else if (phase == Phase.DATA) {
                    if (cursor == saved.size()) { cursor=0; phase=entities==null?Phase.VERIFY:Phase.ENTITY_APPLY; continue; }
                    var value=saved.get(cursor++);
                    if (!value.already()) writeData(value.target(), targetCheckpoint(value));
                } else if (phase == Phase.ENTITY_APPLY) {
                    if(cursor==entities.size()){cursor=0;phase=Phase.VERIFY;continue;}
                    entities.apply(cursor++);
                } else if (phase == Phase.VERIFY) {
                    if (cursor == saved.size()) {cursor=0;if(entities!=null){phase=Phase.ENTITY_VERIFY;continue;}finishVerification();return;}
                    var value=saved.get(cursor++);
                    verifyCurrent(value, targetCheckpoint(value));
                    if(!value.desired().canSurvive(value.target().level(),value.target().snapshot().pos()))
                        throw new IllegalStateException("Восстановленному блоку не хватает опоры/связанных блоков: "+value.target().level().dimension().location()+" "+value.target().snapshot().pos());
                } else if (phase == Phase.ENTITY_VERIFY) {
                    if(cursor==entities.size()){cursor=0;finishVerification();return;}
                    entities.verify(cursor++);
                } else if(phase==Phase.PLAYER_VERIFY) {
                    if(!resourceCheck.isDone()) {
                        if(System.nanoTime()>=resourceDeadline)throw new IllegalStateException("Повторная проверка сохранённых инвентарей не завершилась за 30 секунд");
                        return;
                    }
                    resourceCheck.join();resourceCheck=null;players.recheckLive();phase=Phase.READY;return;
                } else if (phase == Phase.UNDO_ENTITIES) {
                    if(cursor==entities.size()){cursor=0;phase=Phase.UNDO_STATES;continue;}
                    entities.undo(cursor++);
                } else if (phase == Phase.UNDO_STATES) {
                    if (cursor == saved.size()) { cursor=0; phase=Phase.UNDO_DATA; continue; }
                    var value=saved.get(cursor++);
                    if (!value.already()) writeState(value.target(), RollbackEngine.strictReplayState(value.target().level(), value.before().state()));
                } else if (phase == Phase.UNDO_DATA) {
                    if (cursor == saved.size()) { phase=Phase.ABORTED; finishAbort(); return; }
                    var value=saved.get(cursor++);
                    if (!value.already()) { writeData(value.target(), value.before()); verifyCurrent(value,value.before()); }
                }
                used++;
            }
        } catch (Throwable problem) {
            if(phase==Phase.QUARANTINED)throw new IllegalStateException(failure,problem);
            String detail=problem.getMessage()==null?problem.getClass().getSimpleName():problem.getMessage();
            if (phase == Phase.UNDO_STATES || phase == Phase.UNDO_DATA || phase == Phase.UNDO_ENTITIES || phase == Phase.ABORTED) {
                phase=Phase.QUARANTINED;
                failure += "; компенсация не завершена: " + detail + ". Блокировки сохранены.";
                throw new IllegalStateException(failure,problem);
            }
            cancel(detail);
        }
    }

    private net.minecraft.server.MinecraftServer server(){return targets.isEmpty()?entities.server():targets.getFirst().level().getServer();}
    private void startJournal() {
        if(saved.isEmpty()){writeJournal();return;}
        var inputs=saved.stream().map(value->new RecoveryItemBalance.Input(value.target().level().dimension().location().toString(),
                value.target().snapshot().pos(),value.before(),targetCheckpoint(value))).toList();
        long remaining=Math.max(0,memoryLimit-memoryBytes-(entities==null?0:entities.backupBytes()));
        RecoveryPlayerDependencies.Check dependencies=null;
        if(players!=null) {
            var frontiers=new ArrayList<RecoveryPlayerDependencies.Frontier>();
            for(var value:saved) {
                var checkpoints=value.target().snapshot().checkpoints();int found=-1;
                for(int i=0;i<checkpoints.size();i++)if(RollbackEngine.sameCheckpoint(value.before(),checkpoints.get(i)))found=i;
                frontiers.add(new RecoveryPlayerDependencies.Frontier(value.target().level().dimension().location().toString(),
                        value.target().snapshot().pos(),found,checkpoints.size()-1));
            }
            players.begin(frontiers);dependencies=players.check(true);
        }
        var capturedDependencies=dependencies;var cancellation=resourcesCancelled;
        var fluidLimits=CreateSupport.available()?com.roften.avilixlogger.compat.create.CreateFluidRecovery.limits():RecoveryFluidBalance.Limits.NONE;
        resourceCheck=RecoveryJournal.io(()->{
            var changes=List.<RecoveryItemBalance.Change>of();
            if(capturedDependencies!=null){capturedDependencies.verify(remaining,cancellation::get);changes=capturedDependencies.changes(remaining);}
            long extra=0;for(var change:changes)extra+=128+PayloadSizeEstimator.estimateTags(change.before(),change.after());
            RecoveryItemBalance.verify(inputs,changes,Math.max(0,remaining-extra),cancellation::get);
            RecoveryFluidBalance.verify(inputs,fluidLimits,Math.max(0,remaining-extra),cancellation::get);return null;
        });
        resourceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);phase=Phase.RESOURCE_CHECK;
    }
    private void finishVerification() {
        if(players==null||!players.active()){phase=Phase.READY;return;}
        var check=players.check(false);long remaining=Math.max(0,memoryLimit-memoryBytes-(entities==null?0:entities.backupBytes()));
        var cancellation=resourcesCancelled;
        resourceCheck=RecoveryJournal.io(()->{check.verify(remaining,cancellation::get);return null;});
        resourceDeadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);phase=Phase.PLAYER_VERIFY;
    }
    private void writeJournal() {
        if (preflightOnly) { phase=Phase.READY; return; }
        var keys=completionEvents();
        var images=entities==null?List.<RecoveryJournal.EntityImage>of():entities.images();
        if(saved.isEmpty()&&keys.isEmpty()&&images.isEmpty()){phase=Phase.STATES;return;}
        var blocks=saved.stream().map(value->new RecoveryJournal.Block(value.target().level().dimension().location().toString(),value.target().snapshot().pos(),value.before(),targetCheckpoint(value))).toList();
        journal=RecoveryJournal.prepare(server(),blocks,images,keys);journalDeadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(2);phase=Phase.WRITE_JOURNAL;
    }

    private void capture(Target target) {
        if(fluidDependencies.contains(new Address(target.level(),target.snapshot().pos()))) {
            reserveStorage(target);var current=RollbackEngine.currentCheckpoint(target.level(),target.snapshot().pos());
            var block=new RollbackEngine.PreparedBlockSnapshot(target.snapshot().pos(),NbtSerde.toSnbt(current.state()),null,null,ActionType.BLOCK_ENTITY_NBT_CHANGE,
                    current.state(),current.be(),current.slots(),current.state(),List.of(current));
            memoryBytes+=320+PayloadSizeEstimator.estimateTags(current.state(),current.be(),current.slots());
            if(memoryBytes>memoryLimit)throw new IllegalStateException("Неизменяемые части ёмкости превышают оставшийся лимит памяти");
            saved.add(new Saved(new Target(target.level(),block),current,target.level().getBlockState(block.pos()),true));
            desired.put(new Address(target.level(),block.pos()),target.level().getBlockState(block.pos()));return;
        }
        var block=target.snapshot();
        String invalid=RollbackEngine.validatePreparedBlock(target.level(),block);
        boolean already="replay_already_current".equals(invalid);
        if (invalid!=null && !already) throw new IllegalStateException(invalid+": "+target.level().dimension().location()+" "+block.pos());
        var state=RollbackEngine.strictReplayState(target.level(),block.stateTag());
        if (state==null) throw new IllegalStateException("invalid_block_snapshot: "+block.pos());
        reserveStorage(target);
        var be=target.level().getBlockEntity(block.pos());
        if (be instanceof net.minecraft.world.Container container) CartRestoreLocks.container(container);
        var before=RollbackEngine.currentCheckpoint(target.level(),block.pos());
        memoryBytes+=160+PayloadSizeEstimator.estimateTags(before.state(),before.be(),before.slots());
        if (memoryBytes>memoryLimit) throw new IllegalStateException("Резервные снимки всей постройки превышают лимит памяти; мир не изменён");
        saved.add(new Saved(target,before,state,already));
        desired.put(new Address(target.level(),block.pos()),state);
    }
    private void reserveStorage(Target target) {
        var block=target.snapshot();
        if(CreateSupport.available()) {
            var owner=com.roften.avilixlogger.compat.create.CreateFluidRecovery.controller(target.level().getBlockEntity(block.pos()));
            if(owner!=null&&!desired.containsKey(new Address(target.level(),owner)))
                throw new IllegalStateException("replay_fluid_controller_outside_scene: "+target.level().dimension().location()+" "+block.pos()+" → "+owner+"; мир не изменён");
        }
        StorageRestoreGuard.reserve(target.level(),block.pos());
    }

    private static RollbackEngine.ReplayCheckpoint targetCheckpoint(Saved value) {
        var block=value.target().snapshot();
        return new RollbackEngine.ReplayCheckpoint(block.stateTag(),block.beTag(),block.slotsTag());
    }
    private static void verifyCurrent(Saved value, RollbackEngine.ReplayCheckpoint expected) {
        var target=value.target();
        if (!RollbackEngine.sameCheckpoint(RollbackEngine.currentCheckpoint(target.level(),target.snapshot().pos()),expected))
            throw new IllegalStateException("Состояние блока/NBT/слотов отличается от снимка: "+target.level().dimension().location()+" "+target.snapshot().pos());
    }
    private BlockState plannedOrCurrent(ServerLevel level,BlockPos pos) {
        var planned=desired.get(new Address(level,pos));
        if (planned!=null) return planned;
        if (level.getChunkSource().getChunkNow(pos.getX()>>4,pos.getZ()>>4)==null) throw new IllegalStateException("Чанк связанного блока не загружен: "+pos);
        return level.getBlockState(pos);
    }
    private void verifyLinkedBlocks(Saved value) {
        var state=value.desired(); var level=value.target().level(); var pos=value.target().snapshot().pos();
        if (state.getBlock() instanceof DoorBlock) {
            var half=state.getValue(DoorBlock.HALF);
            var other=plannedOrCurrent(level,half==DoubleBlockHalf.LOWER?pos.above():pos.below());
            if (!other.is(state.getBlock()) || other.getValue(DoorBlock.HALF)==half
                    || other.getValue(DoorBlock.FACING)!=state.getValue(DoorBlock.FACING)
                    || other.getValue(DoorBlock.HINGE)!=state.getValue(DoorBlock.HINGE)
                    || other.getValue(DoorBlock.OPEN)!=state.getValue(DoorBlock.OPEN))
                throw new IllegalStateException("Неполная/несогласованная пара двери: "+pos);
        }
        if (state.getBlock() instanceof BedBlock) {
            var part=state.getValue(BedBlock.PART); var facing=state.getValue(BedBlock.FACING);
            var other=plannedOrCurrent(level,pos.relative(part==BedPart.FOOT?facing:facing.getOpposite()));
            if (!other.is(state.getBlock()) || other.getValue(BedBlock.PART)==part || other.getValue(BedBlock.FACING)!=facing)
                throw new IllegalStateException("Неполная/несогласованная кровать: "+pos);
        }
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE)!=ChestType.SINGLE) {
            var other=plannedOrCurrent(level,pos.relative(ChestBlock.getConnectedDirection(state)));
            if (!other.is(state.getBlock()) || other.getValue(ChestBlock.TYPE)==ChestType.SINGLE
                    || other.getValue(ChestBlock.TYPE)==state.getValue(ChestBlock.TYPE)
                    || other.getValue(ChestBlock.FACING)!=state.getValue(ChestBlock.FACING))
                throw new IllegalStateException("Неполный/несогласованный двойной сундук: "+pos);
        }
    }
    private static void writeState(Target target,BlockState state) {
        if (state==null) throw new IllegalStateException("Состояние резервного снимка не прочитано");
        boolean restoring=CartAuditContext.restoringJournal(true);
        try {
            var level=target.level(); var pos=target.snapshot().pos();
            if (!level.getBlockState(pos).equals(state)) level.removeBlockEntity(pos);
            level.setBlock(pos,state,2|16|32|64);
            if (!level.getBlockState(pos).equals(state)) throw new IllegalStateException("Блок отклонил состояние: "+pos);
        } finally { CartAuditContext.restoringJournal(restoring); }
    }
    private static void writeData(Target target,RollbackEngine.ReplayCheckpoint data) {
        boolean restoring=CartAuditContext.restoringJournal(true);
        try {
            var level=target.level(); var pos=target.snapshot().pos();
            if (data.be()!=null && !NbtSerde.readBlockEntity(level,pos,data.be())) throw new IllegalStateException("NBT блока не загружен: "+pos);
            if (data.slots()!=null && !ContainerSlotSnapshot.apply(level,pos,data.slots())) throw new IllegalStateException("Слоты блока не восстановлены: "+pos);
            StorageRestoreGuard.reserve(level,pos);
            if (level.getBlockEntity(pos) instanceof net.minecraft.world.Container container) CartRestoreLocks.container(container);
        } finally { CartAuditContext.restoringJournal(restoring); }
    }

    /** Flushes the verified scene and retires its durable undo record before releasing locks. */
    public void commit() {
        if (!ready()) throw new IllegalStateException("Сцена ещё не проверена целиком");
        if (preflightOnly) throw new IllegalStateException("Предпросмотр не может изменять мир");
        if(players!=null)players.recheckLive();
        if(journal!=null) {
            RecoveryCheckpoint.flush(server());
            // An uncommitted scene can still be undone exactly by WAL. Before committing its
            // unlogged world changes, revoke other historical player/block proofs durably.
            if(changedWorld())CaptureCoverage.unrecordedMutation();
            try {journal.commit(completionEvents());journal=null;}
            catch(Throwable problem) {
                // Deletion may have reached disk even if its directory fsync failed. Never begin
                // another mutation/undo without a guaranteed undo record after this boundary.
                phase=Phase.QUARANTINED;
                failure="Результат полностью сохранён, но журнал не удалось надёжно закрыть; область остаётся защищённой: "+problem;
                throw new IllegalStateException(failure,problem);
            }
        }
        collectReport();
        var notifications=new ArrayList<Target>();
        for(var value:saved)if(!value.already())notifications.add(value.target());
        phase=Phase.CLOSED;
        release();
        for (var target:notifications) {
            try {
                var pos=target.snapshot().pos(); var state=target.level().getBlockState(pos);
                target.level().sendBlockUpdated(pos,state,state,3);
                target.level().blockUpdated(pos,state.getBlock());
                target.level().updateNeighborsAt(pos,state.getBlock());
            } catch (Throwable problem) {
                com.roften.avilixlogger.AvilixLoggerMod.LOGGER.warn("[AvilixLogger] Committed recovery neighbour update failed at {} {}",target.level().dimension().location(),target.snapshot().pos(),problem);
            }
        }
    }
    private List<Long> completionEvents() {
        var events=new LinkedHashSet<Long>();if(entities!=null)events.addAll(entities.newEvents());if(players!=null)events.addAll(players.newEvents());
        if(events.size()>100_000)throw new IllegalStateException("Слишком много связанных квитанций в одной сцене");return List.copyOf(events);
    }

    public RollbackReport completePreflight() {
        if (!preflightOnly || !ready() || changedWorld() || journal != null)
            throw new IllegalStateException("Предпросмотр ещё не проверен");
        if(players!=null)players.recheckLive();
        collectReport();
        report.livePreviewVerified = true;
        phase = Phase.CLOSED;
        release();
        return report;
    }

    private void collectReport() {
        for (var value:saved) {
            if (value.already()) report.onSkipped("replay_already_current");
            else {
                report.onApplied(value.target().snapshot().type()); report.blocksRestored++;
                if (value.target().snapshot().beTag()!=null) report.blockEntitiesRestored++;
                if (value.target().snapshot().slotsTag()!=null) report.containersRestored++;
            }
        }
        if(entities!=null)entities.report(report);
    }
    private void finishAbort() {
        if(journal!=null) {
            if(changedWorld())RecoveryCheckpoint.flush(server());
            journal.retire();journal=null;
        }
        release();
    }
    private void release() {
        resourcesCancelled.set(true);resourceCheck=null;
        if(players!=null)players.release();
        if(entities!=null)entities.release();
        if (ownsLocks) { CartRestoreLocks.clear(); CartAuditContext.rollbackActive=false; ownsLocks=false; }
        saved.clear(); desired.clear();fluidDependencies.clear();targets.clear(); chunks.clear();
    }
}
