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
    private static final java.time.format.DateTimeFormatter GAP_TIME =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss z").withZone(java.time.ZoneId.systemDefault());
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
        if(Thread.currentThread()!=serverThread || !CaptureTypePolicy.enabled(30) && fault==null){queue.put(value,bytes);return true;}
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
        if (!com.roften.avilixlogger.LoggerConfig.isEnabled()) return "Запись отключена в конфигурации.";
        var gap=active();if(gap==null)gap=last;
        String mode=CaptureTypePolicy.enabled(30)?"Переполнение: временная пауза с автоматическим возобновлением.":"Переполнение: ожидание очереди, серверный тик может задерживаться.";
        if(gap==null)return "Запись событий работает. "+mode;
        String queue=switch(gap.queue()){case "cpu"->"обработка снимков";case "events"->"события";case "journal"->"запись на диск";default->gap.queue();};
        String reason=switch(gap.reason()){case "FULL"->"достигнут лимит очереди: обработка медленнее поступления";case "BUSY"->"очередь не освободила блокировку за 0,2 мс";case "OVERSIZED"->"крупный снимок не принят: буфер ещё занят";default->gap.reason();};
        return (gap.until()==0?"ЗАПИСЬ ВРЕМЕННО ПРИОСТАНОВЛЕНА с ":"Запись работает. Последняя пауза: ")+GAP_TIME.format(java.time.Instant.ofEpochMilli(gap.since()))
            +".\nПричина: "+queue+" — "+reason+".\nОтклонено заданий: "+gap.rejected()+"; пропущено проверок: "+gap.suppressedChecks()+" (это не число игровых действий)."
            +(gap.until()==0?"\nПосле освобождения очередей запись возобновится автоматически. Пропущенные действия восстановить нельзя.":"\nПауза завершена в "+GAP_TIME.format(java.time.Instant.ofEpochMilli(gap.until()))+"; интервал передан на запись в журнал.")
            +"\nРежим можно изменить: Главный экран → Запись событий → Переполнение. "+mode;
    }
}
