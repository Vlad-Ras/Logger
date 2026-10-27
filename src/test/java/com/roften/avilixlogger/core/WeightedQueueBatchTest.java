package com.roften.avilixlogger.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** The real queue without Minecraft: pressure wakes batching only, reservations remain until completion. */
public final class WeightedQueueBatchTest {
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception{run();}
    static void run()throws Exception{
        for(boolean producerFirst:new boolean[]{false,true})wakeBatch(producerFirst);
        drainBeforeFlush();closeAndInterrupt();normalPoll();
        System.out.println("WEIGHTED_QUEUE_BATCH_OK: pressure before/during batch wait, queued drain, no early reservation release, normal poll, interrupted producer and close");
    }
    private static void await(java.util.function.BooleanSupplier ready)throws Exception{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(!ready.getAsBoolean()&&System.nanoTime()<deadline)Thread.sleep(2);check(ready.getAsBoolean(),"fixture timed out");}
    private static void wakeBatch(boolean producerFirst)throws Exception{
        var queue=new WeightedQueue<Integer>(1,100);queue.put(1,100);var held=queue.poll(10);var error=new AtomicReference<Throwable>();
        var producer=new Thread(()->{try{queue.put(2,20);}catch(Throwable failure){error.set(failure);}},"batch-pressure-producer");producer.setDaemon(true);
        var result=new FutureTask<WeightedQueue.Work<Integer>>((Callable<WeightedQueue.Work<Integer>>)()->queue.pollForBatch(60_000));var consumer=new Thread(result,"batch-consumer");consumer.setDaemon(true);
        try{
            if(producerFirst){producer.start();await(queue::hasWaitingProducers);consumer.start();}
            else{consumer.start();await(()->consumer.getState()==Thread.State.TIMED_WAITING);producer.start();}
            check(result.get(2,TimeUnit.SECONDS)==null,"empty batching poll returned a fabricated work item");
            check(producer.isAlive()&&queue.retainedBytes()==100,"flush request released in-flight bytes/permits before persistence");
            queue.complete(held);held=null;producer.join(3000);check(!producer.isAlive()&&error.get()==null,"producer did not resume");var work=queue.poll(10);check(work.value()==2,"waiting producer lost");queue.complete(work);check(queue.awaitEmpty(System.nanoTime()+TimeUnit.SECONDS.toNanos(1)),"queue did not drain");
        }finally{queue.close();if(held!=null)queue.complete(held);producer.join(3000);consumer.join(3000);}
    }
    private static void drainBeforeFlush()throws Exception{
        var queue=new WeightedQueue<Integer>(3,100);for(int i=1;i<=3;i++)queue.put(i,20);var first=queue.poll(10);var producer=new Thread(()->queue.put(4,20),"batch-drain-producer");producer.setDaemon(true);producer.start();
        try{await(queue::hasWaitingProducers);var second=queue.pollForBatch(60_000);var third=queue.pollForBatch(60_000);check(second.value()==2&&third.value()==3&&queue.pollForBatch(60_000)==null,"batching flushed before draining queued rows");check(queue.retainedBytes()==60,"batch collection freed reservations");queue.complete(first);queue.complete(second);queue.complete(third);producer.join(3000);check(!producer.isAlive(),"drain producer stuck");var fourth=queue.poll(10);check(fourth.value()==4,"drain changed order");queue.complete(fourth);}finally{queue.close();producer.join(3000);}
    }
    private static void closeAndInterrupt()throws Exception{
        var queue=new WeightedQueue<Integer>(1,10);queue.put(1,10);var held=queue.poll(10);var interrupted=new AtomicBoolean();var failure=new AtomicReference<Throwable>();
        var producer=new Thread(()->{try{queue.put(2,1);interrupted.set(Thread.currentThread().isInterrupted());}catch(Throwable error){failure.set(error);}},"interrupted-batch-producer");producer.setDaemon(true);producer.start();await(queue::hasWaitingProducers);producer.interrupt();check(queue.pollForBatch(60_000)==null,"interrupted wait lost pressure state");queue.complete(held);producer.join(3000);check(interrupted.get()&&failure.get()==null&&!queue.hasWaitingProducers(),"interrupt/producer accounting changed");var second=queue.poll(10);queue.complete(second);queue.close();
        var closing=new WeightedQueue<Integer>(1,10);closing.put(1,10);var accepted=closing.poll(10);var closed=new AtomicBoolean();var waiter=new Thread(()->{try{closing.put(2,1);}catch(IllegalStateException expected){closed.set(true);}},"closed-batch-producer");waiter.setDaemon(true);waiter.start();await(closing::hasWaitingProducers);closing.close();waiter.join(3000);check(closed.get()&&!closing.hasWaitingProducers()&&closing.retainedBytes()==10,"close lost accepted work or retained waiter");closing.complete(accepted);
    }
    private static void normalPoll()throws Exception{
        var queue=new WeightedQueue<Integer>(1,10);queue.put(1,10);var held=queue.poll(10);var producer=new Thread(()->{try{queue.put(2,1);}catch(IllegalStateException expected){}},"normal-poll-pressure");producer.setDaemon(true);producer.start();await(queue::hasWaitingProducers);
        var result=new FutureTask<WeightedQueue.Work<Integer>>((Callable<WeightedQueue.Work<Integer>>)()->queue.poll(60_000));var consumer=new Thread(result,"normal-poll-consumer");consumer.setDaemon(true);consumer.start();
        try{try{result.get(30,TimeUnit.MILLISECONDS);throw new AssertionError("normal consumer became a batch flush poll");}catch(TimeoutException expected){}}finally{queue.close();queue.complete(held);producer.join(3000);consumer.join(3000);}
    }
}
