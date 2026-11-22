package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Isolated metadata fixture around actual native world/WAL operations; never grants AuthCore authorization. */
public final class CaptureMutationNativeSmoke {
    private static final BlockPos POS=new BlockPos(128,188,128);
    private static final CaptureCoverage.Settings FULL=new CaptureCoverage.Settings("native-mutation-fixture",true);
    public static void process(ServerLevel level,String mode) throws Exception {
        level.getChunk(POS);for(var side:net.minecraft.core.Direction.values())level.getChunk(POS.relative(side));
        var root=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT);
        var file=root.resolve("data/mutation-native-fixture/coverage");var probe=file.resolveSibling("old-token");
        if(mode.equals("mutation-fence-verify")) {
            check(level.getBlockState(POS).is(Blocks.STONE),"startup did not undo the saved post-fence world");
            var token=UUID.fromString(Files.readString(probe).trim());refused(file,token);
            var ledger=new CaptureCoverage.Ledger(file,FULL,true);check(!token.equals(ledger.token()),"restart revived pre-fence token");refused(file,token);
            System.out.println("MUTATION_FENCE_RESTART_OK: crash after durable proof revocation but before commit undoes saved blocks; old proofs stay revoked after re-enable");
            level.getServer().halt(false);return;
        }
        check(mode.equals("mutation-fence-crash"),"unknown mutation process mode");
        level.removeBlockEntity(POS);level.setBlock(POS,Blocks.STONE.defaultBlockState(),2|16|32|64);var before=checkpoint(level);
        var desired=new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(Blocks.COBBLESTONE.defaultBlockState()),null,null);
        var ledger=new CaptureCoverage.Ledger(file,FULL,true);var field=CaptureCoverage.class.getDeclaredField("active");field.setAccessible(true);field.set(null,ledger);
        var token=ledger.token();var scene=scene(level,target(desired,List.of(before,desired)));drain(scene);check(scene.ready(),scene.failure());
        Files.writeString(probe,token.toString());try(var channel=java.nio.channels.FileChannel.open(probe,StandardOpenOption.WRITE)){channel.force(true);}
        try(var channel=java.nio.channels.FileChannel.open(probe.getParent(),StandardOpenOption.READ)){channel.force(true);}
        RecoveryCheckpoint.flush(level.getServer());CaptureCoverage.unrecordedMutation();
        System.out.println("MUTATION_COMMIT_FENCE_FORCED_CRASH_READY");System.out.flush();Runtime.getRuntime().halt(76);
    }
    public static void run(ServerLevel level) throws Exception {
        level.getChunk(POS);for(var side:net.minecraft.core.Direction.values())level.getChunk(POS.relative(side));
        var original=checkpoint(level);boolean originalRestoring=CartAuditContext.restoring();
        var field=CaptureCoverage.class.getDeclaredField("active");field.setAccessible(true);var previous=field.get(null);
        var directory=level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).resolve("data/mutation-native-fixture");
        var file=directory.resolve("coverage");RecoveryScene scene=null;
        try {
            level.removeBlockEntity(POS);level.setBlock(POS,Blocks.BARREL.defaultBlockState(),2|16|32|64);
            var empty=checkpoint(level);((Container)level.getBlockEntity(POS)).setItem(0,new ItemStack(Items.DIAMOND,5));
            var ledger=new CaptureCoverage.Ledger(file,FULL,true);field.set(null,ledger);var first=ledger.token();
            var report=RollbackEngine.applyPreparedBlockBatch(level,List.of(target(empty,null)));
            check(report.blocksRestored==1&&((Container)level.getBlockEntity(POS)).isEmpty(),"ordinary native rollback did not restore container");
            check(ledger.token()==null,"ordinary rollback left old proof valid");refused(file,first);
            var bytes=Files.readAllBytes(file);long generation=CaptureCoverage.generation();
            for(int i=0;i<20;i++)RollbackEngine.applyPreparedBlockBatch(level,List.of(target(empty,null)));
            check(Arrays.equals(bytes,Files.readAllBytes(file))&&generation==CaptureCoverage.generation(),"ordinary batches rewrote the fence");
            CartAuditContext.rollbackActive=true;CaptureCoverage.settleMutations(true);check(ledger.token()==null,"active job reopened coverage");CartAuditContext.rollbackActive=false;
            ledger.settleUnrecorded(FULL,true);check(!first.equals(ledger.token()),"ordinary completion revived old proof");refused(file,first);

            // No external participant or quantity change is involved: exact before/after WAL compensation.
            level.removeBlockEntity(POS);level.setBlock(POS,Blocks.STONE.defaultBlockState(),2|16|32|64);var before=checkpoint(level);
            var desired=new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(Blocks.COBBLESTONE.defaultBlockState()),null,null);
            var token=ledger.token();var prepared=target(desired,List.of(before,desired));
            scene=scene(level,prepared).preflightOnly();drain(scene);check(scene.ready(),scene.failure());scene.completePreflight();
            check(token.equals(ledger.token())&&level.getBlockState(POS).is(Blocks.STONE),"preview revoked evidence or changed blocks");
            scene=scene(level,prepared);advance(scene,RecoveryScene.Phase.DATA);
            check(token.equals(ledger.token()),"WAL-protected application revoked retry evidence before commit");
            scene.cancel("mutation fixture cancel");drain(scene);
            check(scene.aborted()&&level.getBlockState(POS).is(Blocks.STONE)&&token.equals(ledger.token()),"WAL compensation lost exact state or proof period");
            scene=scene(level,prepared);drain(scene);check(scene.ready()&&token.equals(ledger.token()),"verified scene revoked its own proof early");scene.commit();
            check(level.getBlockState(POS).is(Blocks.COBBLESTONE)&&ledger.token()==null,"committed scene did not revoke other stale proofs");refused(file,token);
            ledger.settleUnrecorded(FULL,true);

            if(CreateSupport.available()) {
                var detached=new CompoundTag();var components=new CompoundTag();components.put("create:minecart_contraption_data",new CompoundTag());detached.put("components",components);
                var prior=ledger.token();bytes=Files.readAllBytes(file);
                com.roften.avilixlogger.compat.create.CartRollbackCoordinator.stop();
                check(prior.equals(ledger.token())&&Arrays.equals(bytes,Files.readAllBytes(file)),"empty Create shutdown revoked history");
                com.roften.avilixlogger.compat.create.CartRecoveryContents.validate(detached,level.registryAccess());
                check(prior.equals(ledger.token())&&Arrays.equals(bytes,Files.readAllBytes(file)),"detached Create validation revoked live history");
                boolean old=CartAuditContext.restoring(true);
                try {level.setBlock(POS,Blocks.STONE.defaultBlockState(),2|16|32|64);}
                finally {CartAuditContext.restoring(old);}
                check(ledger.token()==null,"Create-style native restoring scope did not fence mutation");refused(file,prior);
                ledger.settleUnrecorded(FULL,true);
                System.out.println("CREATE_MUTATION_FENCE_NATIVE_OK: detached Create codecs preserve metadata; shared native restoring scope durably revokes it");
            }

            level.setBlock(POS,Blocks.STONE.defaultBlockState(),2|16|32|64);before=checkpoint(level);prepared=target(desired,List.of(before,desired));
            scene=scene(level,prepared);drain(scene);check(scene.ready(),scene.failure());
            var blocked=file.resolveSibling(file.getFileName()+".pending");Files.createDirectory(blocked);
            try {scene.commit();throw new AssertionError("commit ignored failed proof revocation");}
            catch(IllegalStateException expected){check(expected.getMessage().contains("отозвать"),"wrong commit fence failure: "+expected);}
            scene.cancel("metadata I/O failure");drain(scene);Files.delete(blocked);
            check(scene.aborted()&&level.getBlockState(POS).is(Blocks.STONE)&&!CartAuditContext.rollbackActive,"failed commit fence did not compensate/release");
            System.out.println("CAPTURE_MUTATION_NATIVE_OK: real container rollback, one fence per batches, preview, WAL cancel preserves epoch, commit revokes epoch and metadata failure compensates exact blocks");
        } finally {
            if(scene!=null&&!scene.aborted()&&scene.phase()!=RecoveryScene.Phase.CLOSED){scene.cancel("native mutation cleanup");drain(scene);}
            boolean old=CartAuditContext.restoringJournal(true);
            try {level.removeBlockEntity(POS);level.setBlock(POS,NbtSerde.readBlockState(level,original.state()),2|16|32|64);
                if(original.be()!=null)NbtSerde.readBlockEntity(level,POS,original.be());if(original.slots()!=null)ContainerSlotSnapshot.apply(level,POS,original.slots());}
            finally {CartAuditContext.restoringJournal(old);CartAuditContext.restoringJournal(originalRestoring);field.set(null,previous);}
            try(var walk=Files.walk(directory)){for(var path:walk.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
    private static RecoveryScene scene(ServerLevel level,RollbackEngine.PreparedBlockSnapshot target){return new RecoveryScene(List.of(new RecoveryScene.Target(level,target)),32L*1024*1024,128);}
    private static RollbackEngine.PreparedBlockSnapshot target(RollbackEngine.ReplayCheckpoint value,List<RollbackEngine.ReplayCheckpoint> checkpoints) {
        return new RollbackEngine.PreparedBlockSnapshot(POS,NbtSerde.toSnbt(value.state()),NbtSerde.toSnbt(value.be()),NbtSerde.toSnbt(value.slots()),ActionType.BLOCK_ENTITY_NBT_CHANGE,
                value.state(),value.be(),value.slots(),checkpoints==null?null:checkpoints.getFirst().state(),checkpoints);
    }
    private static RollbackEngine.ReplayCheckpoint checkpoint(ServerLevel level){return new RollbackEngine.ReplayCheckpoint(NbtUtils.writeBlockState(level.getBlockState(POS)),NbtSerde.snapshotBlockEntityAt(level,POS),ContainerSlotSnapshot.snapshotTag(level,POS));}
    private static PlayerBlockTransfer.Proof proof(UUID token){return new PlayerBlockTransfer.Proof(1,1,UUID.randomUUID(),UUID.randomUUID(),"minecraft:overworld",POS,1,null,null,1,"fixture",token);}
    private static void refused(Path file,UUID token){try{CaptureCoverage.verify(file,List.of(proof(token)));throw new AssertionError("stale proof accepted");}catch(IllegalStateException expected){check(expected.getMessage().contains("coverage_gap"),"wrong proof failure: "+expected);}}
    private static void step(RecoveryScene scene){scene.step(1,System.nanoTime()+TimeUnit.SECONDS.toNanos(1),8);if(Set.of(RecoveryScene.Phase.RESOURCE_CHECK,RecoveryScene.Phase.WRITE_JOURNAL,RecoveryScene.Phase.PLAYER_VERIFY).contains(scene.phase()))java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));}
    private static void drain(RecoveryScene scene){for(int i=0;i<10000&&!scene.ready()&&!scene.aborted()&&!scene.quarantined();i++)step(scene);check(scene.ready()||scene.aborted(),"mutation scene stalled: "+scene.phase()+" "+scene.failure());}
    private static void advance(RecoveryScene scene,RecoveryScene.Phase phase){for(int i=0;i<10000&&!scene.aborted()&&scene.phase()!=phase;i++)step(scene);check(scene.phase()==phase,"missing phase: "+scene.phase()+" "+scene.failure());}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
