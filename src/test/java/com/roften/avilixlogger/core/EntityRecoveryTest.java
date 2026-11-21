package com.roften.avilixlogger.core;

import net.minecraft.nbt.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

final class EntityRecoveryTest {
    static void run() throws Exception {
        UUID id=UUID.randomUUID();var root=node(id,"minecraft:marker");
        check(EntityRecoverySnapshot.require(root,"minecraft:marker",id).nodes().size()==1,"valid entity tree rejected");
        var duplicate=root.copy();var children=new ListTag();children.add(root.copy());duplicate.put("Passengers",children);
        reason(duplicate,"entity_duplicate_uuid_in_tree");
        var player=root.copy();player.putString("id","minecraft:player");reason(player,"player_entity_snapshot_forbidden");
        var bad=root.copy();var wrong=new ListTag();wrong.add(StringTag.valueOf("not an entity"));bad.put("Passengers",wrong);reason(bad,"entity_invalid_passengers");
        var deep=root.copy();var cursor=deep;
        for(int i=0;i<35;i++){var child=node(UUID.randomUUID(),"minecraft:marker");var list=new ListTag();list.add(child);cursor.put("Passengers",list);cursor=child;}
        reason(deep,"entity_tree_limit");
        var sentinel=root.copy();var attachments=new CompoundTag();var controller=new CompoundTag();controller.putString("Type","EMPTY");attachments.put("create:minecart_controller",controller);sentinel.put("neoforge:attachments",attachments);
        check(EntityRecoveryEvidence.linkedState(sentinel)==null,"immutable empty controller refused");
        controller.putString("UnknownResource","must-not-ignore");check("entity_attachments_graph_required".equals(EntityRecoveryEvidence.linkedState(sentinel)),"unknown mod attachment data silently ignored");
        var spawn=row(10,100,id,root,ActionType.ENTITY_SPAWN);var death=row(11,101,id,root,ActionType.ENTITY_DEATH);
        var plan=new EntityReplayPlan();plan.accept(spawn,true);check(plan.finish().size()==1,"birth snapshot lost");
        plan=new EntityReplayPlan();plan.accept(spawn,true);plan.accept(death,true);check(plan.finish().isEmpty(),"birth+death became a resurrection");
        plan=new EntityReplayPlan();plan.accept(death,true);var removed=plan.finish().getFirst();
        check(removed.before()!=null&&removed.after()==null,"death before-image became a replay birth");
        plan=new EntityReplayPlan();plan.accept(spawn,true);plan.accept(death,false);check(!plan.issues().isEmpty()&&plan.finish().isEmpty(),"filtered entity lifecycle was partly selected");
        var cow=node(UUID.randomUUID(),"minecraft:cow");var cowRow=row(12,102,cow.getUUID("UUID"),cow,ActionType.ENTITY_SPAWN);
        plan=new EntityReplayPlan();plan.accept(cowRow,true);check(plan.finish().getFirst().policyIssue().equals("entity_resource_proof_required"),"survival creation was treated as free");
        plan=new EntityReplayPlan();plan.accept(spawn,true);var born=plan.finish();
        EntityReplayPlan.verifyHistory(history(List.of(spawn)),born);
        try{EntityReplayPlan.verifyHistory(history(List.of(spawn,death)),born);throw new AssertionError("later lifecycle was hidden");}
        catch(IllegalStateException expected){check(expected.getMessage().contains("entity_later_or_unselected_history"),"wrong freshness error");}
        indexAndJournal(root,id);
        System.out.println("ENTITY_RECOVERY_PLAN_OK: bounded trees, duplicate/player/wrong passengers refusal, forward lifecycle, filters, resource proof, later history, raw disk UUID collision and v1/v2 journal/receipts");
    }
    private static void indexAndJournal(CompoundTag marker,UUID id) throws Exception {
        var folder=Files.createTempDirectory("avilix-entity-index-");
        try {
            var header=ByteBuffer.allocate(12288).order(ByteOrder.BIG_ENDIAN);header.putInt(1023*4,(2<<8)|1);
            Files.write(folder.resolve("r.-1.-2.mca"),header.array());
            Files.write(folder.resolve("r.0.0.mca"),new byte[0]);
            var data=new CompoundTag();var entities=new ListTag();entities.add(marker);data.put("Entities",entities);
            var source=new EntitySavedIndex.Source("minecraft:overworld",folder,pos->{check(pos.x==-1&&pos.z==-33,"negative region address reversed");return CompletableFuture.completedFuture(Optional.of(data));});
            var other=new EntitySavedIndex.Source("minecraft:the_nether",folder,source.reader());
            var result=EntitySavedIndex.scan(List.of(source,other),Set.of(id),new EntitySavedIndex.Progress(),()->false);
            check(result.locations(id).size()==2,"saved UUID in another dimension was missed");
            try{EntitySavedIndex.scan(List.of(new EntitySavedIndex.Source(source.dimension(),folder,pos->CompletableFuture.completedFuture(Optional.empty()))),Set.of(id),new EntitySavedIndex.Progress(),()->false);throw new AssertionError("unreadable occupied entity chunk treated as absence");}
            catch(java.io.IOException expected){}
            try{EntitySavedIndex.scan(List.of(source),Set.of(id),new EntitySavedIndex.Progress(),()->true);throw new AssertionError("cancelled UUID scan continued");}
            catch(CancellationException expected){}
            var file=folder.resolve("operation.scene");
            var scene=new RecoveryJournal.SceneData(List.of(),List.of(new RecoveryJournal.EntityImage("minecraft:overworld",id,"minecraft:marker",null,marker,List.of(88L))),List.of(88L));
            RecoveryJournal.write(file,scene);check(RecoveryJournal.readScene(file).equals(scene),"entity undo record changed exact NBT/event IDs");
            new RecoveryJournal.Handle(file,CompletableFuture.completedFuture(null)).commit(List.of(88L));
            check(!Files.exists(file)&&RecoveryJournal.readCommitted(folder).contains(88L),"commit did not retain a durable receipt");
            // Marker publication must precede compaction. A failure creating the tiny receipt
            // leaves the full committed record available for startup reconciliation.
            var retry=folder.resolve("interrupted.scene");
            RecoveryJournal.write(retry,new RecoveryJournal.SceneData(List.of(),List.of(),List.of(89L)));
            Files.createDirectory(folder.resolve("interrupted.receipt.tmp"));
            try{new RecoveryJournal.Handle(retry,CompletableFuture.completedFuture(null)).commit(List.of(89L));throw new AssertionError("injected receipt publication failure ignored");}
            catch(CompletionException expected){}
            check(Files.exists(folder.resolve("interrupted.committed"))&&!Files.exists(retry),"failed compaction lost the commit boundary");
            var old=folder.resolve("old.scene");RecoveryJournal.write(old,List.of());
            rewriteV1(old);check(RecoveryJournal.readScene(old).entities().isEmpty(),"legacy block-only journal became incompatible");
        } finally {try(var files=Files.walk(folder)){for(var file:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
    }
    private static void rewriteV1(Path path) throws Exception {
        var in=new java.io.DataInputStream(new java.io.ByteArrayInputStream(Files.readAllBytes(path)));int magic=in.readInt();var tag=NbtIo.read(in,NbtAccounter.create(1024*1024));
        tag.putInt("Version",1);tag.remove("Kind");tag.remove("Entities");tag.remove("Events");
        var bytes=new java.io.ByteArrayOutputStream();var out=new java.io.DataOutputStream(bytes);out.writeInt(magic);NbtIo.write(tag,out);out.flush();
        out.write(java.security.MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));out.flush();Files.write(path,bytes.toByteArray());
    }
    static CompoundTag node(UUID id,String type) {return NbtSerde.fromSnbt("{id:'"+type+"',UUID:"+uuid(id)+",Pos:[1d,190d,2d],Rotation:[0f,0f],Motion:[0d,0d,0d]}");}
    private static String uuid(UUID id){var tag=new CompoundTag();tag.putUUID("UUID",id);return tag.get("UUID").toString();}
    static LogEntry row(long number,long ts,UUID id,CompoundTag tag,ActionType type){var row=new LogEntry();row.id=number;row.ts=ts;row.entityUuid=id;row.entityType=tag.getString("id");row.entityNbt=tag.toString();row.type=type;row.dim="minecraft:overworld";return row;}
    private static LogStorage history(List<LogEntry> rows){return new LogStorage(){
        public void append(LogEntry row){throw new UnsupportedOperationException();}
        public List<LogEntry> query(LogQuery q){check(q.requireDetails&&q.actorName==null&&q.actorNames.isEmpty()&&q.minPos==null&&q.dim.equals("*"),"freshness query hid another actor/position/dimension");return rows;}
        public List<LogEntry> queryReverse(LogQuery q){throw new UnsupportedOperationException();}public void shutdown(){}
    };}
    private static void reason(CompoundTag tag,String expected){check(expected.equals(EntityRecoverySnapshot.validate(tag,tag.getString("id"),tag.getUUID("UUID"))),"incorrect entity validation reason, expected "+expected);}
    private static void check(boolean okay,String reason){if(!okay)throw new AssertionError(reason);}
}
