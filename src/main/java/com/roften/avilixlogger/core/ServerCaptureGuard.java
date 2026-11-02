package com.roften.avilixlogger.core;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/** Only new server capture stops. Already accepted worker tasks continue to drain. */
public final class ServerCaptureGuard {
    public record Gap(long since,long until,String queue,String reason,long rejected,long suppressedChecks){}
    private static final class Fault {
        final long since=System.currentTimeMillis();final String queue,reason;
        final LongAdder rejected=new LongAdder(),suppressed=new LongAdder();
        Fault(String queue,String reason){this.queue=queue;this.reason=reason;rejected.increment();}
        Gap snapshot(long until){return new Gap(since,until,queue,reason,rejected.sum(),suppressed.sum());}
    }
    private static volatile Thread serverThread;
    private static volatile Fault fault;
    private static volatile Gap last;
    private static volatile long generation;
    private static volatile Consumer<Gap> listener=gap->{};
    private ServerCaptureGuard(){}
    public static void start(Thread thread,Consumer<Gap> notices){fault=null;last=null;listener=notices;serverThread=thread;generation++;}
    public static void stop(){var current=fault;if(current!=null)last=current.snapshot(System.currentTimeMillis());fault=null;serverThread=null;listener=gap->{};}
    public static boolean paused(){return fault!=null;}
    public static long generation(){return generation;}
    public static boolean captureAllowed(){var current=fault;if(current==null||Thread.currentThread()!=serverThread)return true;current.suppressed.increment();return false;}
    static <T> boolean enqueue(WeightedQueue<T> queue,T value,long bytes){
        if(Thread.currentThread()!=serverThread){queue.put(value,bytes);return true;}
        var current=fault;if(current!=null){current.rejected.increment();return false;}
        var offered=queue.offer(value,bytes);if(offered==WeightedQueue.Offer.ACCEPTED)return true;
        current=new Fault(queue.name(),offered.name());generation++;fault=current;
        var notice=current.snapshot(0);var callback=listener;
        try{CompletableFuture.runAsync(()->callback.accept(notice)).exceptionally(failure->null);}catch(RuntimeException ignored){}
        return false;
    }
    public static Gap active(){var current=fault;return current==null?null:current.snapshot(0);}
    public static Gap last(){return last;}
    static Gap prepareResume(){var current=fault;return current==null?null:current.snapshot(System.currentTimeMillis());}
    /** Used only after the caller has accepted the gap receipt and checked drained queues. */
    static void resume(Gap gap){last=gap;generation++;fault=null;}
    public static String report(){
        var gap=active();if(gap==null)gap=last;
        if(gap==null)return "Защита TPS включена; аварийных остановок захвата не было.";
        return (gap.until()==0?"ЗАХВАТ ОСТАНОВЛЕН ДЛЯ ЗАЩИТЫ TPS":"Захват возобновлён; последний пропуск")
            +". Начало: "+java.time.Instant.ofEpochMilli(gap.since())+"; "+(gap.until()==0?"интервал ещё открыт":"конец: "+java.time.Instant.ofEpochMilli(gap.until()))
            +". Очередь: "+gap.queue()+"; причина: "+gap.reason()+". Отклонено заданий: "+gap.rejected()+"; пропущено проверок захвата: "+gap.suppressedChecks()
            +". Это не точное число игровых событий. Уже принятые записи дописываются. После освобождения очередей: Действия → Производительность → Возобновить захват.";
    }
}
