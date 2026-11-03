package com.roften.avilixlogger.core;
import java.util.concurrent.*;

/** Full/held downstream must not block server admission or discard accepted worker work. */
final class ServerCaptureGuardTest {
    static void check(boolean ok,String text){if(!ok)throw new AssertionError(text);}
    static void run()throws Exception{
        var notice=new CompletableFuture<ServerCaptureGuard.Gap>();var queue=new WeightedQueue<Integer>("fixture",1,100);
        ServerCaptureGuard.start(Thread.currentThread(),notice::complete);
        try{
            check(ServerCaptureGuard.enqueue(queue,1,70),"first server work refused");var held=queue.poll(10);long began=System.nanoTime();
            check(!ServerCaptureGuard.enqueue(queue,2,70),"full queue accepted overflow");
            check(System.nanoTime()-began<TimeUnit.SECONDS.toNanos(1),"server waited for held consumer");
            check(ServerCaptureGuard.paused()&&notice.get(2,TimeUnit.SECONDS).reason().equals("FULL"),"fault/notice missing");
            check(!CaptureTypePolicy.allows(ActionType.CONTAINER_PUT,"player:menu",null),"server snapshot gate remained active");
            check(queue.retainedBytes()==70&&queue.statistics().outstanding()==1&&queue.statistics().waits()==0,"rejected work retained or producer waited");
            check(queue.pollForBatch(60_000)==null&&queue.hasPressure(),"nonblocking pressure did not wake partial batch");
            var done=new CompletableFuture<Boolean>();var worker=new Thread(()->{
                check(CaptureTypePolicy.allows(ActionType.CONTAINER_PUT,"player:menu",null),"accepted worker capture suppressed");done.complete(ServerCaptureGuard.enqueue(queue,3,20));
            },"already-accepted-worker");worker.setDaemon(true);worker.start();queue.complete(held);
            check(done.get(2,TimeUnit.SECONDS),"accepted worker handoff discarded");var last=queue.poll(100);check(last!=null&&last.value()==3,"accepted order/data changed");queue.complete(last);worker.join(2000);
            var gap=ServerCaptureGuard.prepareResume();check(gap.rejected()==1&&gap.suppressedChecks()==1&&gap.until()>=gap.since(),"gap counter/interval inaccurate");
            check(LoggerRuntime.resumeCapture().contains("AuthCore")&&ServerCaptureGuard.paused(),"resume bypassed AuthCore");
            var marker=LoggerRuntime.captureGapEntry(gap);check(marker.type==ActionType.CAPTURE_GAP&&marker.actorUuid==null&&EntryPositionEvidence.read(marker)==null,"gap fabricated actor/position");
            ServerCaptureGuard.resume(gap);check(ServerCaptureGuard.captureAllowed()&&ServerCaptureGuard.last().equals(gap),"resume lost gap");
        }finally{queue.close();ServerCaptureGuard.stop();}
        oversized();lockContention();journalFlush();backpressureChoice();deathReserve();
        System.out.println("SERVER_CAPTURE_GUARD_OK: protected full/oversized/contended admission, server gate, accepted worker drain, gap and partial batch flush");
    }
    private static void deathReserve()throws Exception {
        var q=new WeightedQueue<Integer>("fixture",1,100);
        ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        int previous=CaptureTypePolicy.mask();CaptureTypePolicy.install(0);
        try {
            check(ServerCaptureGuard.enqueue(q,1,100),"bulk first row lost");
            check(!ServerCaptureGuard.enqueue(q,2,1)&&ServerCaptureGuard.paused(),"bulk did not pause");
            check(CaptureTypePolicy.allows(ActionType.PLAYER_DEATH,null,null)&&CaptureTypePolicy.allows(ActionType.PLAYER_KILL,null,null),"pause muted lifecycle policy");
            check(q.offerReserved(3,40,2,80)==WeightedQueue.Offer.ACCEPTED,"paused death reserve refused first row");
            check(q.offerReserved(4,40,2,80)==WeightedQueue.Offer.ACCEPTED,"paired killer row refused");
            check(q.offerReserved(5,1,2,80)==WeightedQueue.Offer.FULL,"death reserve exceeded count/byte budget");
            check(q.offerReserved(6,81,2,80)==WeightedQueue.Offer.OVERSIZED,"oversized receipt escaped reserve bound");
            check(q.statistics().waits()==0&&q.retainedBytes()==180,"reserve blocked or memory accounting changed");
            for(int value:new int[]{1,3,4}){var work=q.poll(10);check(work.value()==value,"reserve reordered accepted work");q.complete(work);}
            check(q.retainedBytes()==0,"reserve retained payloads after drain");
            CaptureTypePolicy.install(CaptureTypePolicy.changed(0,29,false));
            check(!CaptureTypePolicy.allows(ActionType.PLAYER_DEATH,null,null),"reserve bypassed explicit death disable");
        }finally{q.close();ServerCaptureGuard.stop();CaptureTypePolicy.install(previous);}
    }
    private static void backpressureChoice()throws Exception {
        int previous=CaptureTypePolicy.mask();var q=new WeightedQueue<Integer>(1,10);
        CaptureTypePolicy.install(CaptureTypePolicy.changed(0,30,false));ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        try {
            q.put(1,5);var held=q.poll(100);
            var release=new Thread(()->{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);while(q.statistics().waitingProducers()==0 && System.nanoTime()<deadline)Thread.yield();q.complete(held);});release.start();
            check(ServerCaptureGuard.enqueue(q,2,5)&&!ServerCaptureGuard.paused(),"no-pause mode paused or lost accepted work");
            release.join(2000);check(q.statistics().waits()==1,"no-pause mode did not apply bounded backpressure");q.complete(q.poll(100));
        }finally{q.close();ServerCaptureGuard.stop();CaptureTypePolicy.install(previous);}
    }
    private static void oversized(){var q=new WeightedQueue<Integer>(1,10);ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        try{check(ServerCaptureGuard.enqueue(q,1,11)&&q.retainedBytes()==11&&!ServerCaptureGuard.paused(),"single large snapshot was lost");
            check(!ServerCaptureGuard.enqueue(q,2,11)&&ServerCaptureGuard.active().reason().equals("OVERSIZED"),"two oversized snapshots exceeded retained bound");
        }finally{q.close();ServerCaptureGuard.stop();}}

    private static void lockContention()throws Exception{
        var q=new WeightedQueue<Integer>(1,10);var field=WeightedQueue.class.getDeclaredField("lock");field.setAccessible(true);var lock=(java.util.concurrent.locks.ReentrantLock)field.get(q);
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);
        var worker=new Thread(()->{lock.lock();try{held.countDown();release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}finally{lock.unlock();}},"held-queue-lock");worker.setDaemon(true);worker.start();
        check(held.await(2,TimeUnit.SECONDS),"lock fixture failed");ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        try{check(!ServerCaptureGuard.enqueue(q,1,5)&&ServerCaptureGuard.active().reason().equals("BUSY"),"server waited for queue lock");}
        finally{release.countDown();worker.join(2000);q.close();ServerCaptureGuard.stop();}
    }
    private static void journalFlush()throws Exception{
        var dir=java.nio.file.Files.createTempDirectory("guard-journal");PendingLogStorage pending=null;ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        try{
            pending=new PendingLogStorage(dir,1,100_000,1000,60_000);var first=AsyncPipelineTest.row("accepted-before-pause");pending.append(first);
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
            while(pending.queueStatistics().queued()!=0&&System.nanoTime()<end)Thread.sleep(2);
            check(pending.queueStatistics().outstanding()==1,"fixture did not hold partial batch");
            pending.append(AsyncPipelineTest.row("refused-after-pause"));check(ServerCaptureGuard.paused(),"journal overflow not suspended");
            while(pending.queueStatistics().outstanding()!=0&&System.nanoTime()<end)Thread.sleep(2);
            check(pending.queueStatistics().outstanding()==0,"partial batch waited for 60s timer");
            var files=new DiskLogJournal(dir).pending(10);check(files.size()==1,"journal batch count changed");var rows=DiskLogJournal.read(files.getFirst());check(rows.size()==1&&rows.getFirst().id==first.id,"accepted row lost or rejected row persisted");
        }finally{if(pending!=null)pending.shutdown();ServerCaptureGuard.stop();try(var files=java.nio.file.Files.walk(dir)){for(var f:files.sorted(java.util.Comparator.reverseOrder()).toList())java.nio.file.Files.deleteIfExists(f);}}
    }
}
