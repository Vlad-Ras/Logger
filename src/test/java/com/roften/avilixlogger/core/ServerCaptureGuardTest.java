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
        oversized();lockContention();journalFlush();
        System.out.println("SERVER_CAPTURE_GUARD_OK: protected full/oversized/contended admission, server gate, accepted worker drain, gap and partial batch flush");
    }
    private static void oversized(){var q=new WeightedQueue<Integer>(1,10);ServerCaptureGuard.start(Thread.currentThread(),gap->{});
        try{check(!ServerCaptureGuard.enqueue(q,1,11)&&q.retainedBytes()==0&&ServerCaptureGuard.active().reason().equals("OVERSIZED"),"server exceeded queue cap");}finally{q.close();ServerCaptureGuard.stop();}}
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
