package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import java.nio.file.*;
import java.util.*;

/** Separate-JVM failure injection, packaged exclusively in the development source set. */
@EventBusSubscriber(modid="avilixlogger")
public final class EntityRecoveryProcessSmoke {
    private static final UUID UNDO=UUID.fromString("35a00000-0000-4000-8000-000000000001"),COMMIT=UUID.fromString("35a00000-0000-4000-8000-000000000002");
    @SubscribeEvent public static void joining(EntityJoinLevelEvent event) {
        if("entity-undo-crash".equals(System.getProperty("avilixlogger.recoveryCrash"))&&!event.loadedFromDisk()
                &&event.getEntity().getUUID().equals(UNDO)&&RecoveryEntityReservations.active()&&CartAuditContext.restoring()) {
            System.out.println("ENTITY_COMPENSATION_FORCED_CRASH_READY: stopped inside production startup undo, before entity publication");
            System.out.flush();Runtime.getRuntime().halt(74);
        }
    }
    static void run(ServerLevel level,String mode) throws Exception {
        if(mode.equals("entity-undo-crash"))throw new AssertionError("startup compensation did not reach the entity restore hook");
        var root=level.getServer().getWorldPath(LevelResource.ROOT);var folder=root.resolve("data/avilixlogger-recovery");
        level.getChunk(EntityRecoveryNativeSmoke.POS);
        boolean previous=CartAuditContext.restoring(true);
        try {
            if(mode.equals("entity-delete-crash")) {
                level.setBlock(EntityRecoveryNativeSmoke.POS,Blocks.AIR.defaultBlockState(),2|16);
                var entity=EntityType.MARKER.create(level);entity.setUUID(UNDO);entity.setPos(EntityRecoveryNativeSmoke.POS.getX()+.25,EntityRecoveryNativeSmoke.POS.getY(),EntityRecoveryNativeSmoke.POS.getZ()+.25);
                var image=NbtSerde.snapshotEntity(level,entity);image.getCompound("data").putString("Probe","exact-before-image");entity.load(image);
                EntityRecoveryNativeSmoke.check(level.addFreshEntity(entity),"baseline marker not accepted");
                Files.writeString(root.resolve("entity-undo-fixture.snbt"),image.toString());RecoveryCheckpoint.flush(level.getServer());
                var row=row(image,UNDO,7313500000001L,ActionType.ENTITY_DEATH);
                var scene=EntityRecoveryNativeSmoke.scene(level,EntityRecoveryNativeSmoke.prepare(List.of(row)),true,List.of(row));
                EntityRecoveryNativeSmoke.drain(scene);EntityRecoveryNativeSmoke.check(scene.ready(),scene.failure());
                EntityRecoveryNativeSmoke.check(level.getEntity(UNDO)==null&&level.getBlockState(EntityRecoveryNativeSmoke.POS).is(Blocks.STONE),"deleted entity/block scene not staged");
                RecoveryCheckpoint.flush(level.getServer());halt(73,"ENTITY_DELETE_FORCED_CRASH_READY");return;
            }
            if(mode.equals("entity-undo-verify")) {
                var expected=NbtSerde.fromSnbt(Files.readString(root.resolve("entity-undo-fixture.snbt")));
                var actual=level.getEntity(UNDO);
                EntityRecoveryNativeSmoke.check(actual!=null&&expected.equals(NbtSerde.snapshotEntity(level,actual))&&level.getBlockState(EntityRecoveryNativeSmoke.POS).isAir(),"repeated compensation lost exact marker/block state");
                EntityRecoveryNativeSmoke.check(count(folder,".scene")==0,"startup undo retained pending record");
                System.out.println("ENTITY_REPEATED_COMPENSATION_NATIVE_OK: two actual JVM crashes, second inside startup undo; exact marker NBT and block restored");
                level.getServer().halt(false);return;
            }
            if(mode.equals("entity-commit-crash")) {
                level.setBlock(EntityRecoveryNativeSmoke.POS,Blocks.AIR.defaultBlockState(),2|16);
                var row=EntityRecoveryNativeSmoke.birth(level,EntityType.MARKER,false);row.entityUuid=COMMIT;row.id=7313500000002L;
                var image=NbtSerde.fromSnbt(row.entityNbt);image.putUUID("UUID",COMMIT);row.entityNbt=image.toString();
                Files.writeString(root.resolve("entity-commit-fixture.snbt"),image.toString());
                var scene=EntityRecoveryNativeSmoke.scene(level,EntityRecoveryNativeSmoke.prepare(List.of(row)),true,List.of(row));
                EntityRecoveryNativeSmoke.drain(scene);EntityRecoveryNativeSmoke.check(scene.ready(),scene.failure());
                Path journal;try(var files=Files.list(folder)){journal=files.filter(path->path.toString().endsWith(".scene")).findFirst().orElseThrow();}
                Files.createDirectory(folder.resolve(journal.getFileName().toString().replace(".scene",".receipt.tmp")));
                try{scene.commit();throw new AssertionError("injected receipt failure ignored");}catch(IllegalStateException expected){EntityRecoveryNativeSmoke.check(scene.quarantined(),"commit uncertainty triggered another mutation");}
                EntityRecoveryNativeSmoke.check(count(folder,".committed")==1&&count(folder,".scene")==0,"full committed record lost");
                halt(75,"ENTITY_COMMIT_FORCED_CRASH_READY");return;
            }
            if(mode.equals("entity-commit-verify")) {
                var image=NbtSerde.fromSnbt(Files.readString(root.resolve("entity-commit-fixture.snbt")));
                var committed=loadForCheck(level,COMMIT);
                EntityRecoveryNativeSmoke.check(committed!=null&&image.equals(NbtSerde.snapshotEntity(level,committed))
                        &&level.getBlockState(EntityRecoveryNativeSmoke.POS).is(Blocks.STONE),"startup undid a published committed result");
                EntityRecoveryNativeSmoke.check(count(folder,".committed")==0&&count(folder,".scene")==0,"startup did not compact committed record");
                committed.discard();
                var row=row(image,COMMIT,7313500000002L,ActionType.ENTITY_SPAWN);
                var scene=EntityRecoveryNativeSmoke.scene(level,EntityRecoveryNativeSmoke.prepare(List.of(row)),false,List.of(row));
                EntityRecoveryNativeSmoke.drain(scene);EntityRecoveryNativeSmoke.check(scene.ready(),scene.failure());scene.commit();
                EntityRecoveryNativeSmoke.check(level.getEntity(COMMIT)==null&&scene.report().skippedReasons.containsKey("entity_recovery_receipt_completed"),"receipt did not prevent second delivery after restart");
                level.setBlock(EntityRecoveryNativeSmoke.POS,Blocks.AIR.defaultBlockState(),2|16);
                System.out.println("ENTITY_COMMIT_RESTART_NATIVE_OK: saved result retained; full committed record compacted; repeated delivery refused after manual removal");
                level.getServer().halt(false);return;
            }
            throw new AssertionError("Unknown entity crash mode: "+mode);
        } finally {CartAuditContext.restoring(previous);}
    }
    private static net.minecraft.world.entity.Entity loadForCheck(ServerLevel level,UUID id) {
        // A FULL block chunk at ServerStartedEvent does not imply that its entity
        // deserializer has run. Use the same queued loading path as production.
        var manager=((com.roften.avilixlogger.mixin.RecoveryEntityLevelAccessor)level).avilixlogger$entityManager();
        var chunk=new net.minecraft.world.level.ChunkPos(EntityRecoveryNativeSmoke.POS);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        RecoveryEntityReservations.begin(level.getServer(),Set.of(id));
        try {
            RecoveryEntityReservations.hold(level,chunk);
            level.getServer().managedBlock(()->{
                manager.tick();
                if(System.nanoTime()>deadline)throw new AssertionError("commit entity loading timed out");
                return manager.areEntitiesLoaded(chunk.toLong());
            });
            RecoveryEntityReservations.verify();
            return RecoveryEntityReservations.live(id);
        } finally {RecoveryEntityReservations.release();CartRestoreLocks.clear();}
    }
    private static LogEntry row(CompoundTag image,UUID id,long event,ActionType action) {
        var row=new LogEntry();row.id=event;row.ts=1;row.type=action;row.entityUuid=id;row.entityType="minecraft:marker";row.entityNbt=image.toString();row.dim="minecraft:overworld";
        row.x=EntityRecoveryNativeSmoke.POS.getX();row.y=EntityRecoveryNativeSmoke.POS.getY();row.z=EntityRecoveryNativeSmoke.POS.getZ();return row;
    }
    private static long count(Path folder,String suffix) throws Exception {try(var files=Files.list(folder)){return files.filter(path->path.toString().endsWith(suffix)).count();}}
    private static void halt(int code,String marker){System.out.println(marker);System.out.flush();Runtime.getRuntime().halt(code);}
}
