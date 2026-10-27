package com.roften.avilixlogger.core;

import com.roften.avilixlogger.compat.supplementaries.*;
import net.minecraft.nbt.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** Deterministic thread/serialization/accounting checks, without a Minecraft world or AuthCore changes. */
final class SnapshotDeltaWorkerTest {
    private record Pair(CompoundTag before,CompoundTag after){}
    private static CompoundTag vanilla(String raw,String filtered){
        var root=new CompoundTag();var side=new CompoundTag();var messages=new ListTag();messages.add(StringTag.valueOf(raw));side.put("messages",messages);
        if(filtered!=null){var f=new ListTag();f.add(StringTag.valueOf(filtered));side.put("filtered_messages",f);}root.put("front_text",side);return root;
    }
    private static CompoundTag book(String text){
        var root=new CompoundTag();var items=new ListTag();var item=new CompoundTag();item.putString("id","minecraft:written_book");item.putInt("count",1);
        var components=new CompoundTag();var content=new CompoundTag();var pages=new ListTag();var page=new CompoundTag();page.putString("raw",text);page.putString("filtered","mask "+text);pages.add(page);
        content.put("pages",pages);content.putBoolean("resolved",true);components.put("minecraft:written_book_content",content);item.put("components",components);items.add(item);root.put("Items",items);return root;
    }
    private static boolean eager(SnapshotDeltaPolicy rule,CompoundTag before,CompoundTag after){return switch(rule){
        case SIGN_TEXT -> SignTextAudit.changed(before,after);
        case SUPPLEMENTARIES_TEXT -> SupplementariesTextAudit.changed(before,after);
        case SUPPLEMENTARIES_DISPLAY -> SupplementariesAutomationAudit.changed(before,after);
    };}
    private static LogEntry candidate(SnapshotDeltaPolicy rule,CompoundTag before,CompoundTag after,String marker){
        var row=row(marker);row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.source="delta:test:"+rule;row.actorUuid=new UUID(3,7);row.actorName="CapturedEditor";row.dim="minecraft:overworld";row.x=-37;row.y=103;row.z=61;
        row.blockBefore="{Name:'supplementaries:notice_board'}";row.blockAfter=row.blockBefore;
        if(before!=null)row.deferSnapshot(LogEntry.SnapshotField.BE_BEFORE,before);if(after!=null)row.deferSnapshot(LogEntry.SnapshotField.BE_AFTER,after);
        row.deferSnapshotDelta(rule);return row;
    }
    static void run()throws Exception{
        equivalence();workerOnlyAndOrdered();inFlightAndVisibility();rejectedResolution();
        System.out.println("SNAPSHOT_DELTA_WORKER_OK: three rules, zero producer comparisons, no-op never encoded, FIFO/metadata/journal, disabled gates, in-flight byte backpressure, visibility/shutdown and idempotent cleanup");
    }
    private static void equivalence(){
        for(var rule:SnapshotDeltaPolicy.values()){
            var before=rule==SnapshotDeltaPolicy.SIGN_TEXT?vanilla("raw","mask"):rule==SnapshotDeltaPolicy.SUPPLEMENTARIES_TEXT?SupplementariesTextAuditTest.signs("upper","lower"):book("page");
            var cases=new ArrayList<Pair>();cases.add(new Pair(before,before.copy()));cases.add(new Pair(null,before));cases.add(new Pair(before,null));cases.add(new Pair(new CompoundTag(),before));
            var style=before.copy();style.putBoolean("Waxed",true);cases.add(new Pair(before,style));
            var changed=before.copy();
            switch(rule){
                case SIGN_TEXT -> changed.getCompound("front_text").getList("filtered_messages",Tag.TAG_STRING).set(0,StringTag.valueOf("new filter"));
                case SUPPLEMENTARIES_TEXT -> changed.getCompound("SignDown").getCompound("TextHolder").getList("message",Tag.TAG_STRING).set(0,StringTag.valueOf("new lower"));
                case SUPPLEMENTARIES_DISPLAY -> changed.getList("Items",Tag.TAG_COMPOUND).getCompound(0).getCompound("components").getCompound("minecraft:written_book_content").putBoolean("resolved",false);
            }
            cases.add(new Pair(before,changed));
            var page=before.copy();page.putInt("PageNumber",1);cases.add(new Pair(before,page));
            var reservations=before.copy();reservations.put("NeoForgeData",new CompoundTag());cases.add(new Pair(before,reservations));
            for(var pair:cases){
                boolean expected=eager(rule,pair.before,pair.after);var pending=candidate(rule,pair.before,pair.after,"equivalence");var resolved=pending.copyForQueue().resolve();
                check((resolved!=null)==expected,"worker filter changed semantics for "+rule);
                if(resolved!=null){resolved.materializeSnapshots();check(Objects.equals(resolved.beBefore,NbtSerde.toSnbt(pair.before))&&Objects.equals(resolved.beAfter,NbtSerde.toSnbt(pair.after)),"accepted evidence lost full snapshots");}
            }
        }
    }
    private static final class ProbeTag extends CompoundTag {
        final AtomicInteger reads=new AtomicInteger(),encodes=new AtomicInteger();
        final CountDownLatch entered,release;volatile boolean armed;
        ProbeTag(CompoundTag value,CountDownLatch entered,CountDownLatch release){merge(value);this.entered=entered;this.release=release;}
        private void inspect(){
            if(!armed)return;
            check(Thread.currentThread().getName().equals("avilixlogger-event-worker"),"detached comparison/encoding escaped ordered worker: "+Thread.currentThread().getName());
            if(reads.getAndIncrement()==0&&entered!=null){entered.countDown();try{check(release.await(5,TimeUnit.SECONDS),"comparison latch timed out");}catch(InterruptedException error){throw new AssertionError(error);}}
        }
        @Override public Tag get(String key){inspect();return super.get(key);}
        @Override public CompoundTag getCompound(String key){inspect();return super.getCompound(key);}
        @Override public String toString(){inspect();encodes.incrementAndGet();return super.toString();}
    }
    private static void workerOnlyAndOrdered()throws Exception{
        var rows=new CopyOnWriteArrayList<LogEntry>();var storage=new AsyncLogStorage(sink(rows),8,256*1024);
        var dropped=new ArrayList<ProbeTag>();var accepted=new ArrayList<ProbeTag>();int mask=CaptureTypePolicy.mask();
        try{
            for(var rule:SnapshotDeltaPolicy.values())for(int i=0;i<40;i++){
                CompoundTag before=rule==SnapshotDeltaPolicy.SIGN_TEXT?vanilla("before",null):rule==SnapshotDeltaPolicy.SUPPLEMENTARIES_TEXT?SupplementariesTextAuditTest.text("before",null):book("before");
                CompoundTag after=rule==SnapshotDeltaPolicy.SIGN_TEXT?vanilla("after",null):rule==SnapshotDeltaPolicy.SUPPLEMENTARIES_TEXT?SupplementariesTextAuditTest.text("after",null):book("after");
                var same1=new ProbeTag(before,null,null);var same2=new ProbeTag(before,null,null);var noop=candidate(rule,same1,same2,"noop");same1.armed=same2.armed=true;storage.append(noop);dropped.add(same1);dropped.add(same2);
                var a=new ProbeTag(before,null,null);var b=new ProbeTag(after,null,null);var row=candidate(rule,a,b,rule+":"+i);a.armed=b.armed=true;storage.append(row);accepted.add(a);accepted.add(b);
                row.actorName="LaterPlayer";row.actorUuid=null;row.dim="later:dimension";row.x=999;row.source="later-source";
            }
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,1,false));var disabled=candidate(SnapshotDeltaPolicy.SUPPLEMENTARIES_DISPLAY,book("before"),book("after"),"disabled");storage.append(disabled);
            CaptureTypePolicy.install(mask);storage.shutdown();
            check(rows.size()==120,"worker omitted changes or persisted no-ops/disabled capture");int index=0;
            for(var rule:SnapshotDeltaPolicy.values())for(int i=0;i<40;i++){
                var row=rows.get(index++);check(row.extra.equals(rule+":"+i)&&row.actorName.equals("CapturedEditor")&&row.actorUuid.equals(new UUID(3,7))&&row.x==-37&&row.y==103&&row.z==61&&row.dim.equals("minecraft:overworld")&&row.source.equals("delta:test:"+rule),"queue reordered rows or read later attribution");
                var replay=GzipJson.fromGzippedJsonBytes(GzipJson.toGzippedJsonBytes(row),LogEntry.class);check(replay.beBefore.equals(row.beBefore)&&replay.beAfter.equals(row.beAfter)&&replay.resolve()==replay,"journal persisted transient policy or lost snapshots");
            }
            check(dropped.stream().allMatch(t->t.encodes.get()==0)&&dropped.stream().mapToInt(t->t.reads.get()).sum()>0,"no-op reached encoding or skipped comparison");
            check(accepted.stream().allMatch(t->t.encodes.get()==1)&&storage.retainedBytes()==0,"accepted payload encoded repeatedly or retained queue bytes");
        }finally{CaptureTypePolicy.install(mask);storage.shutdown();}
    }
    private static void inFlightAndVisibility()throws Exception{
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var rows=new CopyOnWriteArrayList<LogEntry>();var storage=new AsyncLogStorage(sink(rows),1,1024);
        var same=new ProbeTag(book("x".repeat(8192)),entered,release);var before=new ProbeTag(book("x".repeat(8192)),null,null);var noop=candidate(SnapshotDeltaPolicy.SUPPLEMENTARIES_DISPLAY,same,before,"large noop");same.armed=before.armed=true;
        var error=new AtomicReference<Throwable>();Thread next=null;
        try{
            storage.append(noop);check(entered.await(5,TimeUnit.SECONDS),"comparison never reached worker");
            check(storage.retainedBytes()>1024,"in-flight oversized candidate lost its byte reservation");
            next=new Thread(()->{try{storage.append(candidate(SnapshotDeltaPolicy.SUPPLEMENTARIES_DISPLAY,book("a"),book("b"),"after noop"));}catch(Throwable failure){error.set(failure);}},"delta-backpressure-producer");next.start();await(()->storage.backpressureCount()>0,"in-flight comparison did not apply bounded backpressure");
            check(next.isAlive()&&!storage.awaitVisible(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(50)),"visibility/permit completed before no-op resolution");
            release.countDown();next.join(5000);check(!next.isAlive()&&error.get()==null,"no-op failed to release waiting producer");
            check(storage.awaitVisible(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)),"visibility did not finish after no-op drained");storage.shutdown();
            check(rows.size()==1&&rows.getFirst().extra.equals("after noop")&&same.encodes.get()==0&&before.encodes.get()==0&&storage.retainedBytes()==0,"oversized no-op hid next row, encoded or leaked bytes");
        }finally{release.countDown();if(next!=null)next.join(5000);storage.shutdown();}
    }
    private static void rejectedResolution(){
        var pending=candidate(SnapshotDeltaPolicy.SUPPLEMENTARIES_TEXT,SupplementariesTextAuditTest.text("same",null),SupplementariesTextAuditTest.text("same","same"),"rejected");
        try{pending.materializeSnapshots();throw new AssertionError("pending predicate bypassed before encoding");}catch(IllegalStateException expected){}
        check(pending.resolve()==null&&pending.resolve()==null&&pending.copyForQueue().resolve()==null,"rejected candidate resurrected on second resolve/copy");
        check(pending.copyDeferredSnapshot(LogEntry.SnapshotField.BE_BEFORE)==null&&pending.copyDeferredSnapshot(LogEntry.SnapshotField.BE_AFTER)==null&&pending.snapshotBytes()==0,"rejected candidate retained detached payload");
        pending.materializeSnapshots();check(pending.beBefore==null&&pending.beAfter==null,"rejected candidate encoded later");
        var ordinary=row("ordinary");check(ordinary.resolve()==ordinary,"ordinary log row changed");
    }
    private static LogStorage sink(List<LogEntry> rows){return new LogStorage(){public void append(LogEntry row){rows.add(row);}public List<LogEntry> query(LogQuery q){return List.copyOf(rows);}public List<LogEntry> queryReverse(LogQuery q){return List.copyOf(rows);}public void shutdown(){}};}
}
