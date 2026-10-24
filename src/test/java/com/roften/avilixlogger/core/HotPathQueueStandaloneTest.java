package com.roften.avilixlogger.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Runs the actual queue/counters with just a JDK, without Minecraft stubs. */
public final class HotPathQueueStandaloneTest {
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        var queue=new WeightedQueue<Integer>(2,100);
        queue.put(1,70);
        var entered=new CountDownLatch(1);var returned=new CountDownLatch(1);
        var failure=new AtomicReference<Throwable>();
        HotPathMetrics.start();
        Thread producer=new Thread(()->{
            HotPathMetrics.serverThread(Thread.currentThread());entered.countDown();
            try { queue.put(2,40);HotPathMetrics.queued(ActionType.ME_PUT,"ae2:terminal"); }
            catch(Throwable error) { failure.set(error); }
            finally { returned.countDown(); }
        },"fixture-world-thread");
        producer.start();
        check(entered.await(2,TimeUnit.SECONDS),"producer did not start");
        check(!returned.await(30,TimeUnit.MILLISECONDS),"queue exceeded byte bound");
        var first=queue.poll(100);check(first.value()==1,"queue order changed");
        check(queue.retainedBytes()==70,"in-flight payload was released too early");
        queue.complete(first);
        check(returned.await(2,TimeUnit.SECONDS),"producer did not resume after completion");producer.join();
        check(failure.get()==null,"producer failed: "+failure.get());
        var second=queue.poll(100);check(second.value()==2,"resumed event lost");queue.complete(second);
        check(queue.retainedBytes()==0&&queue.backpressureCount()==1,"reservation/wait count incorrect");
        String report=HotPathMetrics.stop();
        check(report.contains("AE2: 1")&&report.contains("Queue waits: 1"),"diagnostics missed the transfer/stall");
        check(!report.substring(report.indexOf("Queue waits:")).contains("0.00 ms server thread"),"world-thread stall not attributed");
        HotPathMetrics.queued(ActionType.ME_PUT,"ae2:terminal");
        check(HotPathMetrics.report().equals(report),"stopped session still changing");
        HotPathMetrics.clear();
        // A single large event is allowed alone, never dropped, and its reservation drains.
        queue.put(3,1000);check(queue.retainedBytes()==1000,"large event reservation missing");
        var large=queue.poll(100);check(large.value()==3,"large event lost");queue.complete(large);
        check(queue.awaitEmpty(System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"queue did not drain");
        queue.close();check(queue.exhausted(),"closed queue not exhausted");
        try { queue.put(4,1);throw new AssertionError("closed queue accepted an event"); }
        catch(IllegalStateException expected) {}
        System.out.println("HOTPATH_QUEUE_STANDALONE_OK: byte backpressure, order, in-flight reservation, measured world-thread stall, stop, oversized payload, shutdown");
    }
}
