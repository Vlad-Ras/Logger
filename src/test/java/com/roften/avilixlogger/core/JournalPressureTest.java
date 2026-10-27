package com.roften.avilixlogger.core;

import java.nio.file.*;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static com.roften.avilixlogger.core.AsyncPipelineTest.*;

/** Real disk publication and deterministic saturation/wakeup regressions; no production world/auth changes. */
final class JournalPressureTest {
    public static void main(String[] args)throws Exception{run();}
    static void run()throws Exception{
        partialBatchPressure(3,0,3,"count");partialBatchPressure(64,2,2,"bytes");partialBatchPressure(64,-1,1,"oversized");
        retryFloodAndReplay();notificationRaces();existence();
        System.out.println("JOURNAL_PRESSURE_OK: count/byte/oversized partial flush before 60s timer, batching/order/full payload/restart, retry flood isolation, notification race, shutdown and pending existence");
    }
    private static void partialBatchPressure(int capacity,int byteSlots,int held,String label)throws Exception{
        Path directory=Files.createTempDirectory("avilix-pressure-"+label);PendingLogStorage storage=null;Thread producer=null;
        var result=new AtomicReference<Throwable>();var done=new CountDownLatch(1);
        try{
            var rows=new ArrayList<LogEntry>();for(int i=0;i<=held;i++){var row=row("pressure "+i+" "+"x".repeat(2048));row.type=ActionType.BLOCK_ENTITY_NBT_CHANGE;row.beBefore="{Book:{Title:'before',Pages:['raw','filtered']}}";row.beAfter="{Book:{Title:'after',Pages:['raw','filtered']}}";rows.add(row);}
            long weight=PayloadSizeEstimator.estimate(rows.getFirst());long limit=byteSlots==0?1024*1024:byteSlots<0?128:weight*byteSlots;
            storage=new PendingLogStorage(directory,capacity,limit,1000,60_000);var target=storage;
            for(int i=0;i<held;i++)storage.append(rows.get(i));
            producer=new Thread(()->{try{target.append(rows.getLast());}catch(Throwable failure){result.set(failure);}finally{done.countDown();}},"journal-pressure-producer");producer.setDaemon(true);producer.start();
            check(done.await(5,TimeUnit.SECONDS),"partial journal batch did not flush under "+label+" pressure before 60s timer");
            check(result.get()==null,"pressure producer failed "+result.get());
            var files=new DiskLogJournal(directory).pending();check(files.size()==1,"pressure fragmented the held batch: "+label);
            var first=DiskLogJournal.read(files.getFirst());check(first.size()==held,"pressure flushed one row at a time instead of draining queued work: "+label);
            storage.shutdown();storage=null;producer.join(5000);check(!producer.isAlive(),"pressure producer did not finish");
            var saved=new ArrayList<LogEntry>();for(var file:new DiskLogJournal(directory).pending())saved.addAll(DiskLogJournal.read(file));
            check(saved.size()==rows.size(),"pressure flush/shutdown lost rows: "+label);
            for(int i=0;i<saved.size();i++)check(saved.get(i).id==rows.get(i).id&&saved.get(i).extra.equals(rows.get(i).extra)&&saved.get(i).beBefore.equals(rows.get(i).beBefore)&&saved.get(i).beAfter.equals(rows.get(i).beAfter),"pressure changed order, ID or mandatory payload: "+label);
        }finally{if(storage!=null)storage.shutdown();if(producer!=null)producer.join(5000);delete(directory);}
    }
    private static void retryFloodAndReplay()throws Exception{
        Path directory=Files.createTempDirectory("avilix-retry-flood");PendingLogStorage storage=null;Thread notifier=null;var stop=new AtomicBoolean();var notifyError=new AtomicReference<Throwable>();
        var calls=new CopyOnWriteArrayList<Long>();var tokens=new CopyOnWriteArrayList<String>();var payloads=new CopyOnWriteArrayList<String>();
        try{
            storage=new PendingLogStorage(directory,4,1024*1024,1,5);var target=storage;var signal=method("signal");
            storage.setDelegate(new BatchLogStorage(){
                public void appendBatch(String token,List<LogEntry> rows)throws IOException{calls.add(System.nanoTime());tokens.add(token);payloads.add(GzipJson.GSON.toJson(rows));throw new IOException("fixture unavailable database");}
                public void append(LogEntry row){throw new AssertionError("batch bypass");}public List<LogEntry> query(LogQuery q){return List.of();}public List<LogEntry> queryReverse(LogQuery q){return List.of();}public void shutdown(){}
            });
            var row=row("retained retry evidence");row.beBefore="{Items:[{id:'minecraft:book',count:1}]}";storage.append(row);
            await(()->!calls.isEmpty(),"sender never attempted delivery");
            notifier=new Thread(()->{try{while(!stop.get()){signal.invoke(target);java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);}}catch(Throwable failure){notifyError.set(failure);}},"journal-publication-notifications");notifier.setDaemon(true);notifier.start();
            await(()->calls.size()>=3,"sender did not retry with backoff");stop.set(true);notifier.join(5000);check(notifyError.get()==null,"notification fixture failed");
            check(calls.get(1)-calls.get(0)>=TimeUnit.MILLISECONDS.toNanos(200)&&calls.get(2)-calls.get(1)>=TimeUnit.MILLISECONDS.toNanos(450),"journal/delegate notifications bypassed retry backoff");
            check(new HashSet<>(tokens).size()==1&&new HashSet<>(payloads).size()==1,"retry changed durable token or payload");
            var closing=new FutureTask<Void>(()->{target.shutdown();return null;});var closer=new Thread(closing,"retry-shutdown");closer.setDaemon(true);closer.start();closing.get(3,TimeUnit.SECONDS);storage=null;
            check(new DiskLogJournal(directory).hasPending(),"failed delivery acknowledged/deleted evidence");
            var replay=new CopyOnWriteArrayList<LogEntry>();storage=new PendingLogStorage(directory,4,1024*1024,1,5);storage.setDelegate(new BatchLogStorage(){
                public void appendBatch(String token,List<LogEntry> rows){replay.addAll(rows);}public void append(LogEntry row){throw new AssertionError("batch bypass");}public List<LogEntry> query(LogQuery q){return List.of();}public List<LogEntry> queryReverse(LogQuery q){return List.of();}public void shutdown(){}
            });
            check(storage.awaitVisible(System.nanoTime()+TimeUnit.SECONDS.toNanos(5)),"restart replay never became visible");
            check(replay.size()==1&&replay.getFirst().id==row.id&&replay.getFirst().beBefore.equals(row.beBefore)&&!new DiskLogJournal(directory).hasPending(),"retry/shutdown/restart lost or duplicated retained evidence");
        }finally{stop.set(true);if(notifier!=null)notifier.join(5000);if(storage!=null)storage.shutdown();delete(directory);}
    }
    private static Method method(String name,Class<?>... parameters)throws Exception{var method=PendingLogStorage.class.getDeclaredMethod(name,parameters);method.setAccessible(true);return method;}
    private static void notificationRaces()throws Exception{
        Path directory=Files.createTempDirectory("avilix-notify-race");var storage=new PendingLogStorage(directory,2,1024*1024,10,60_000);
        try{
            var version=method("wakeupVersion");var signal=method("signal");long observed=(long)version.invoke(storage);signal.invoke(storage);
            var pause=method("pauseIfUnchanged",long.class,long.class);var task=new FutureTask<Void>(()->{pause.invoke(storage,60_000L,observed);return null;});var waiter=new Thread(task,"notification-before-wait");waiter.setDaemon(true);waiter.start();task.get(2,TimeUnit.SECONDS);
            var retry=method("retryPause",long.class,BatchLogStorage.class);var retryTask=new FutureTask<Void>(()->{retry.invoke(storage,60_000L,null);return null;});var retryThread=new Thread(retryTask,"notification-during-backoff");retryThread.setDaemon(true);retryThread.start();await(()->retryThread.getState()==Thread.State.TIMED_WAITING,"retry fixture did not wait");
            for(int i=0;i<100;i++)signal.invoke(storage);
            try{retryTask.get(40,TimeUnit.MILLISECONDS);throw new AssertionError("ordinary notifications interrupted retry delay");}catch(TimeoutException expected){}
            var replacement=new BatchLogStorage(){public void appendBatch(String token,List<LogEntry> rows){throw new AssertionError("empty journal unexpectedly delivered rows");}public void append(LogEntry row){throw new AssertionError("empty journal append");}public List<LogEntry> query(LogQuery q){return List.of();}public List<LogEntry> queryReverse(LogQuery q){return List.of();}public void shutdown(){}};
            storage.setDelegate(replacement);retryTask.get(2,TimeUnit.SECONDS);
            var stopTask=new FutureTask<Void>(()->{retry.invoke(storage,60_000L,replacement);return null;});var stopThread=new Thread(stopTask,"shutdown-during-backoff");stopThread.setDaemon(true);stopThread.start();await(()->stopThread.getState()==Thread.State.TIMED_WAITING,"shutdown retry fixture did not wait");
            storage.shutdown();stopTask.get(2,TimeUnit.SECONDS);
        }finally{storage.shutdown();delete(directory);}
    }
    private static void existence()throws Exception{
        Path directory=Files.createTempDirectory("avilix-existence");try{
            var journal=new DiskLogJournal(directory);check(!journal.hasPending(),"empty journal reported pending");Files.createFile(directory.resolve("unpublished.tmp"));check(!journal.hasPending(),"unpublished tmp became visible");
            var pending=directory.resolve("malformed.jsonl");Files.writeString(pending,"retained invalid evidence");check(journal.hasPending(),"existence lookup ignored malformed pending file");check(Files.readString(pending).equals("retained invalid evidence"),"existence lookup read/modified evidence");Files.delete(pending);check(!journal.hasPending(),"removed pending file still reported");
        }finally{delete(directory);}
    }
    private static void delete(Path directory)throws IOException{try(var paths=Files.walk(directory)){for(var file:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(file);}}
}
