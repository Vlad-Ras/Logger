package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import com.roften.avilixlogger.mixin.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.entity.Visibility;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Dev-only tests of production scene/index/undo/receipt code. Public authorization stays unchanged. */
final class EntityRecoveryNativeSmoke {
    static final BlockPos POS=new BlockPos(145,190,96);
    private static long next=System.currentTimeMillis()*1000L;
    private static List<EntityReplayPlan.Prepared> inspecting=List.of();
    static void run(ServerLevel level) throws Exception {
        level.getChunk(POS);for(var side:net.minecraft.core.Direction.values())level.getChunk(POS.relative(side));
        boolean previous=CartAuditContext.restoring(true);
        var old=level.getBlockState(POS);var oldNbt=NbtSerde.snapshotBlockEntityAt(level,POS);
        var cleanup=new ArrayList<UUID>();
        try {
            level.removeBlockEntity(POS);level.setBlock(POS,Blocks.AIR.defaultBlockState(),2|16);
            var row=birth(level,EntityType.MARKER,false);cleanup.add(row.entityUuid);
            var prepared=prepare(List.of(row)).getFirst();
            var preview=scene(level,List.of(prepared),true,List.of(row)).preflightOnly();
            drain(preview);check(preview.ready()&&!preview.changedWorld(),"joint preview failed: "+preview.failure());
            var previewReport=preview.completePreflight();
            check(previewReport.livePreviewVerified&&previewReport.entitiesRespawned==1&&level.getEntity(row.entityUuid)==null
                    &&level.getBlockState(POS).isAir()&&!RecoveryEntityReservations.active(),"entity preview published a birth or retained its reservation");
            var scene=scene(level,List.of(prepared),true,List.of(row));
            advance(scene,RecoveryScene.Phase.ENTITY_VERIFY);
            check(level.getEntity(row.entityUuid)!=null&&level.getBlockState(POS).is(Blocks.STONE),"joint scene did not stage both blocks and entities");
            boolean restoring=CartAuditContext.restoring(false);
            try {
                var marker=level.getEntity(row.entityUuid);marker.discard();marker.setPos(POS.getX()+10,POS.getY(),POS.getZ());
                check(!marker.isRemoved()&&marker.blockPosition().equals(POS),"reserved entity accepted removal or movement");
            } finally {CartAuditContext.restoring(restoring);}
            scene.cancel("native cross-participant cancellation");drain(scene);
            check(scene.aborted()&&level.getEntity(row.entityUuid)==null&&level.getBlockState(POS).isAir(),"joint compensation left a block/entity behind");
            check(!RecoveryEntityReservations.active()&&!CartAuditContext.rollbackActive,"joint compensation retained entity reservations");

            row=birth(level,EntityType.MARKER,false);cleanup.add(row.entityUuid);prepared=prepare(List.of(row)).getFirst();
            scene=scene(level,List.of(prepared),false,List.of(row));drain(scene);check(scene.ready(),scene.failure());scene.commit();
            var marker=level.getEntity(row.entityUuid);check(marker!=null,"marker was not restored");marker.discard();
            scene=scene(level,List.of(prepared),false,List.of(row));drain(scene);check(scene.ready(),scene.failure());scene.commit();
            check(level.getEntity(row.entityUuid)==null&&scene.report().skippedReasons.containsKey("entity_recovery_receipt_completed"),"committed birth was delivered twice after removal");

            var survival=birth(level,EntityType.COW,false);cleanup.add(survival.entityUuid);
            scene=scene(level,prepare(List.of(survival)),true,List.of(survival));drain(scene);
            check(scene.aborted()&&scene.failure().contains("entity_resource_proof_required")&&level.getBlockState(POS).isAir()&&level.getEntity(survival.entityUuid)==null,
                    "unproven creation partly restored blocks/resources: "+scene.failure());

            for(var type:List.of(EntityType.COW,EntityType.ARMOR_STAND,EntityType.BOAT)) {
                var proven=birth(level,type,true);cleanup.add(proven.entityUuid);
                scene=scene(level,prepare(List.of(proven)),false,List.of(proven));advance(scene,RecoveryScene.Phase.ENTITY_VERIFY);
                var entity=level.getEntity(proven.entityUuid);check(entity!=null,"proven vanilla entity absent: "+type);
                int drops=drops(level);restoring=CartAuditContext.restoring(false);
                try {entity.hurt(level.damageSources().generic(),1000);entity.kill();check(!entity.isRemoved()&&drops(level)==drops,"damage/kill emitted resources during a protected restore: "+type);}
                finally {CartAuditContext.restoring(restoring);}
                drain(scene);check(scene.ready(),scene.failure());scene.commit();
                check(level.getEntity(proven.entityUuid)==entity,"commit replaced native entity identity");
                entity.discard();
            }
            var cart=birth(level,EntityType.MINECART,true);cleanup.add(cart.entityUuid);
            scene=scene(level,prepare(List.of(cart)),true,List.of(cart));drain(scene);
            check(scene.aborted()&&scene.failure().contains("replay_linked_create_entity")&&level.getBlockState(POS).isAir()&&level.getEntity(cart.entityUuid)==null,
                    "Create cart controller was partly restored without its registry: "+scene.failure());
            passengers(level,cleanup);
            savedGhost(level,cleanup);
            hiddenChunk(level,cleanup);
            check(!CartAuditContext.rollbackActive&&!RecoveryEntityReservations.active(),"entity native fixture leaked locks");
            System.out.println("ENTITY_RECOVERY_NATIVE_OK: joint block/entity cancel; durable repeat refusal; unproven survival and unpaired Create controller refusal; proven cow/armorstand/boat; damage/kill freeze; grouped passengers; unloaded UUID; hidden chunk save");
        } finally {
            for(var id:cleanup)for(var world:level.getServer().getAllLevels()){var entity=world.getEntity(id);if(entity!=null)entity.discard();}
            level.removeBlockEntity(POS);level.setBlock(POS,old,2|16);if(oldNbt!=null)NbtSerde.readBlockEntity(level,POS,oldNbt);
            CartAuditContext.restoring(previous);
        }
    }
    private static void passengers(ServerLevel level,List<UUID> cleanup) {
        Entity root=EntityType.COW.create(level),child=EntityType.TEXT_DISPLAY.create(level);
        root.setPos(POS.getX()+.25,POS.getY(),POS.getZ()+.25);child.setPos(root.position());check(child.startRiding(root,true),"passenger fixture mount rejected");
        cleanup.add(root.getUUID());cleanup.add(child.getUUID());
        LogEntry a,b;
        try(var group=EntityRecoveryEvidence.summon()){a=row(level,root,true);b=row(level,child,true);}
        var prepared=prepare(List.of(a,b));check(prepared.size()==1&&prepared.getFirst().eventIds().size()==2,"passenger join rows were not grouped");
        var scene=scene(level,prepared,false,List.of(a,b));drain(scene);check(scene.ready(),scene.failure()+" parent="+a.entityNbt+" child="+b.entityNbt);scene.commit();
        var actual=level.getEntity(root.getUUID());
        check(actual!=null&&actual.getPassengers().size()==1&&actual.getFirstPassenger().getUUID().equals(child.getUUID()),"passenger tree was flattened or duplicated");
        actual.getFirstPassenger().discard();actual.discard();
    }
    private static void savedGhost(ServerLevel level,List<UUID> cleanup) throws Exception {
        var row=birth(level,EntityType.MARKER,false);cleanup.add(row.entityUuid);
        var ghost=NbtSerde.fromSnbt(row.entityNbt);var position=new ListTag();position.add(DoubleTag.valueOf(2048.5));position.add(DoubleTag.valueOf(POS.getY()));position.add(DoubleTag.valueOf(2048.5));ghost.put("Pos",position);
        var chunk=new ChunkPos(BlockPos.containing(2048.5,POS.getY(),2048.5));
        var manager=((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();
        var storage=((RecoveryEntityManagerAccessor<?>)manager).avilixlogger$entityStorage();
        var regions=((RecoveryEntityStorageAccessor)storage).avilixlogger$entityRegionStorage();
        var data=NbtUtils.addCurrentDataVersion(new CompoundTag());var entities=new ListTag();entities.add(ghost);data.put("Entities",entities);data.putIntArray("Position",new int[]{chunk.x,chunk.z});
        regions.write(chunk,data).join();regions.synchronize(true).join();
        check(level.getEntity(row.entityUuid)==null,"saved UUID fixture was already live");
        var scene=scene(level,prepare(List.of(row)),false,List.of(row));drainWithInbox(scene,level);check(scene.ready(),scene.failure());
        var existing=RecoveryEntityReservations.live(row.entityUuid);scene.commit();
        check(existing!=null&&!existing.isRemoved()&&manager.isLoaded(row.entityUuid)&&existing.getX()==2048.5&&scene.report().skippedReasons.containsKey("entity_already_present"),"saved UUID was duplicated or teleported over its current state");
        check(!NbtSerde.restoreEntityFromSnapshot(level,row.entityType,row.entityUuid,row.entityNbt).success(),"generic restore ignored live UUID collision");
        existing.discard();RecoveryCheckpoint.flush(level.getServer());
    }
    private static void hiddenChunk(ServerLevel level,List<UUID> cleanup) {
        var row=birth(level,EntityType.MARKER,false);cleanup.add(row.entityUuid);
        var result=NbtSerde.restoreEntityFromSnapshot(level,row.entityType,row.entityUuid,row.entityNbt);check(result.success(),result.reason());
        var scene=scene(level,prepare(List.of(row)),false,List.of(row));advance(scene,RecoveryScene.Phase.ENTITY_CAPTURE);
        var manager=((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();var chunk=new ChunkPos(POS);
        manager.updateChunkStatus(chunk,Visibility.HIDDEN);
        try {
            RecoveryCheckpoint.flush(level.getServer());
            check(!result.entity().isRemoved()&&manager.isLoaded(row.entityUuid),"saveAll unloaded a reserved hidden UUID");
        } finally {manager.updateChunkStatus(chunk,Visibility.TRACKED);}
        drain(scene);check(scene.ready(),scene.failure());scene.commit();result.entity().discard();
    }
    static LogEntry birth(ServerLevel level,EntityType<?> type,boolean proven) {
        var entity=type.create(level);entity.setPos(POS.getX()+.25,POS.getY(),POS.getZ()+.25);
        if(proven)try(var group=EntityRecoveryEvidence.summon()){return row(level,entity,true);}
        return row(level,entity,false);
    }
    static LogEntry row(ServerLevel level,Entity entity,boolean proven) {
        var row=new LogEntry();row.id=++next;row.ts=System.currentTimeMillis();row.type=ActionType.ENTITY_SPAWN;row.dim=level.dimension().location().toString();
        row.entityUuid=entity.getUUID();row.entityType=net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();row.x=entity.blockPosition().getX();row.y=entity.blockPosition().getY();row.z=entity.blockPosition().getZ();
        var tag=NbtSerde.snapshotEntity(level,entity);if(proven)EntityRecoveryEvidence.capture(tag,entity,level,null);row.entityNbt=tag.toString();return row;
    }
    static List<EntityReplayPlan.Prepared> prepare(List<LogEntry> rows){var plan=new EntityReplayPlan();for(var row:rows)plan.accept(row,true);var result=plan.finish();check(plan.issues().stream().noneMatch(issue->!issue.conditional()),"invalid native entity fixture: "+plan.issues());return result;}
    static RecoveryScene scene(ServerLevel level,List<EntityReplayPlan.Prepared> entities,boolean block,List<LogEntry> rows) {
        inspecting=entities;
        var blocks=new ArrayList<RecoveryScene.Target>();
        if(block) {
            var air=NbtUtils.writeBlockState(Blocks.AIR.defaultBlockState());var stone=NbtUtils.writeBlockState(Blocks.STONE.defaultBlockState());
            blocks.add(new RecoveryScene.Target(level,new RollbackEngine.PreparedBlockSnapshot(POS,stone.toString(),null,null,ActionType.BLOCK_PLACE,stone,null,null,air,List.of(new RollbackEngine.ReplayCheckpoint(air,null,null),new RollbackEngine.ReplayCheckpoint(stone,null,null)))));
        }
        return new RecoveryScene(blocks,entities.stream().map(value->new RecoveryEntityBatch.Target(level,value)).toList(),64L*1024*1024,128,
                active->EntityReplayPlan.verifyHistory(new History(rows),active));
    }
    private record History(List<LogEntry> rows) implements LogStorage {
        public void append(LogEntry row){throw new UnsupportedOperationException();}
        public List<LogEntry> query(LogQuery q){return rows.stream().filter(row->row.ts>=q.sinceTs&&(row.ts>q.cursorTs||row.ts==q.cursorTs&&row.id>q.afterId)).limit(q.limit).toList();}
        public List<LogEntry> queryReverse(LogQuery q){throw new UnsupportedOperationException();}public void shutdown(){}
    }
    static void drain(RecoveryScene scene){for(int n=0;n<20000&&!scene.ready()&&!scene.aborted()&&!scene.quarantined();n++)step(scene);check(scene.ready()||scene.aborted(),"entity scene stalled: "+scene.phase()+" "+scene.failure());}
    private static void drainWithInbox(RecoveryScene scene,ServerLevel level){
        // The whole wait must remain managed, so callbacks queued outside the small
        // step() interval can run before the first server tick grants a time budget.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
        level.getServer().managedBlock(()->{
            step(scene);
            if(System.nanoTime()>deadline)throw new AssertionError("saved UUID loading timeout: "+scene.phase()+" "+scene.failure());
            return scene.ready()||scene.aborted()||scene.quarantined();
        });
        check(scene.ready()||scene.aborted(),"saved UUID loading stalled: "+scene.phase()+" "+scene.failure());
    }
    static void advance(RecoveryScene scene,RecoveryScene.Phase phase){for(int n=0;n<20000&&scene.phase()!=phase&&!scene.aborted()&&!scene.quarantined();n++)step(scene);check(scene.phase()==phase,"entity scene did not reach "+phase+": "+scene.phase()+" "+scene.failure());}
    private static void step(RecoveryScene scene){
        if(scene.phase()==RecoveryScene.Phase.ENTITY_APPLY||scene.phase()==RecoveryScene.Phase.VERIFY||scene.phase()==RecoveryScene.Phase.ENTITY_VERIFY) {
            var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            for(var value:inspecting) {
                var entity=RecoveryEntityReservations.live(value.uuid());var actual=entity==null?null:NbtSerde.snapshotEntity((ServerLevel)entity.level(),entity);
                if(actual!=null&&value.after()!=null&&!value.after().equals(actual))System.out.println("ENTITY_NATIVE_NBT_DIFF expected="+value.after()+" actual="+actual);
            }
        }
        scene.step(1,System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(20),8);
        // This suite runs in ServerStartedEvent, before normal ticks drain the entity inbox.
        var server=net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        for(var level:server.getAllLevels()) {
            level.getChunkSource().pollTask();
            ((RecoveryEntityLevelAccessor)level).avilixlogger$entityManager().tick();
        }
        // The pre-tick test event has no tick-time allowance. managedBlock permits one
        // queued server task (the vanilla entity deserializer), without a world tick.
        boolean[] once={false};server.managedBlock(()->{if(once[0])return true;once[0]=true;return false;});
        if(scene.phase()==RecoveryScene.Phase.ENTITY_INDEX||scene.phase()==RecoveryScene.Phase.WRITE_JOURNAL||scene.phase()==RecoveryScene.Phase.WAIT_CHUNKS||scene.phase()==RecoveryScene.Phase.ENTITY_CAPTURE)java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
    }
    private static int drops(ServerLevel level){return level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,new net.minecraft.world.phys.AABB(POS).inflate(4)).size();}
    static void check(boolean okay,String why){if(!okay)throw new AssertionError(why);}
}
