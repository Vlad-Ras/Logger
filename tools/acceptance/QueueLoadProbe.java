package com.roften.avilixlogger.core;

import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Actual queue on a JDK. Synthetic producer latency is never labelled Minecraft MSPT or FPS. */
public final class QueueLoadProbe {
    private record Payload(int sequence,byte[] data) {}
    private record Result(double p50us,double p95us,double p99us,double maxMs,double rowsPerSecond,
                          long peakBytes,int peakRows,long waits,long longestWaitNs,double allocatedPerRow) {}
    public static void main(String[] args)throws Exception {
        int rows=args.length==0?100_000:Integer.parseInt(args[0]);
        if(rows<1000||rows>1_000_000)throw new IllegalArgumentException("rows: 1000..1000000");
        run(20_000,256);
        var results=new ArrayList<Result>();for(int round=0;round<3;round++)results.add(run(rows,256));
        System.out.println("{\"schema\":1,\"scope\":\"synthetic-queue-only\",\"rows_per_round\":"+rows+",\"rounds\":3,\"payload_bytes\":256,\"results\":[");
        for(int i=0;i<results.size();i++) {
            var r=results.get(i);
            System.out.printf(Locale.ROOT,"%s{\"put_p50_us\":%.3f,\"put_p95_us\":%.3f,\"put_p99_us\":%.3f,\"put_max_ms\":%.3f,\"rows_per_second\":%.1f,\"peak_estimated_bytes\":%d,\"peak_rows\":%d,\"waits\":%d,\"longest_wait_ms\":%.3f,\"producer_allocated_bytes_per_row\":%.2f}%n",
                i==0?"":",",r.p50us,r.p95us,r.p99us,r.maxMs,r.rowsPerSecond,r.peakBytes,r.peakRows,r.waits,r.longestWaitNs/1e6,r.allocatedPerRow);
        }
        System.out.println("],\"controlled_stall_ms\":"+controlledStall()+",\"rows_lost\":0,\"minecraft_mspt\":null,\"client_fps\":null}");
    }
    private static Result run(int count,int bytes)throws Exception {
        var queue=new WeightedQueue<Payload>("probe",4096,16*1024*1024);
        var failure=new AtomicReference<Throwable>();var finished=new CountDownLatch(1);
        var consumer=new Thread(()->{
            try {for(int i=0;i<count;i++) {
                var work=queue.poll(5000);if(work==null)throw new AssertionError("consumer timed out");
                if(work.value().sequence!=i||work.value().data[0]!=(byte)i)throw new AssertionError("order or payload changed");
                queue.complete(work);
            }}catch(Throwable e){failure.set(e);queue.close();}finally{finished.countDown();}
        },"queue-probe-consumer");consumer.setDaemon(true);consumer.start();
        var times=new long[count];var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if(bean.isThreadAllocatedMemorySupported()&&!bean.isThreadAllocatedMemoryEnabled())bean.setThreadAllocatedMemoryEnabled(true);
        long allocated=bean.isThreadAllocatedMemorySupported()?bean.getCurrentThreadAllocatedBytes():-1;
        long started=System.nanoTime();
        for(int i=0;i<count;i++) {
            var data=new byte[bytes];data[0]=(byte)i;var payload=new Payload(i,data);long before=System.nanoTime();
            queue.put(payload,bytes+64);times[i]=System.nanoTime()-before;
        }
        long elapsed=System.nanoTime()-started;
        long allocatedAfter=allocated<0?-1:bean.getCurrentThreadAllocatedBytes();
        if(!finished.await(10,TimeUnit.SECONDS)||failure.get()!=null)throw new AssertionError("consumer failed",failure.get());
        var stats=queue.statistics();if(stats.bytes()!=0||stats.outstanding()!=0)throw new AssertionError("retained payload after drain");
        queue.close();consumer.join(2000);Arrays.sort(times);
        return new Result(times[count/2]/1e3,times[(int)(count*.95)]/1e3,times[(int)(count*.99)]/1e3,times[count-1]/1e6,count/(elapsed/1e9),
            stats.peakBytes(),stats.peakOutstanding(),stats.waits(),stats.maxWaitNanos(),allocated<0?-1:(allocatedAfter-allocated)/(double)count);
    }
    private static double controlledStall()throws Exception {
        var queue=new WeightedQueue<Integer>("held-worker",1,128);queue.put(1,128);var first=queue.poll(10);
        var started=new CountDownLatch(1);var done=new CountDownLatch(1);var failure=new AtomicReference<Throwable>();
        var producer=new Thread(()->{started.countDown();try{queue.put(2,128);}catch(Throwable e){failure.set(e);}finally{done.countDown();}},"probe-world-producer");producer.setDaemon(true);producer.start();
        if(!started.await(2,TimeUnit.SECONDS))throw new AssertionError("producer did not start");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(!queue.hasWaitingProducers()&&System.nanoTime()<deadline)Thread.onSpinWait();
        if(!queue.hasWaitingProducers())throw new AssertionError("queue did not reach controlled saturation");
        if(done.await(100,TimeUnit.MILLISECONDS))throw new AssertionError("full queue unexpectedly accepted another row");
        queue.complete(first);
        if(!done.await(2,TimeUnit.SECONDS)||failure.get()!=null)throw new AssertionError("producer failed",failure.get());
        var last=queue.poll(10);if(last==null||last.value()!=2)throw new AssertionError("saturation lost row");queue.complete(last);
        var state=queue.statistics();queue.close();producer.join(2000);
        if(state.bytes()!=0||state.waits()!=1||state.maxWaitNanos()<TimeUnit.MILLISECONDS.toNanos(80))throw new AssertionError("stall statistics missing");
        return state.maxWaitNanos()/1e6;
    }
}
