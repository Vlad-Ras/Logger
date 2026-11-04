package com.roften.avilixlogger.core;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** Opt-in bounded counters. No world, player, handler or NBT references are retained. */
public final class HotPathMetrics {
    private static volatile Session session;
    private static volatile String lastReport = "Замеры ещё не запускались: /log perf start";
    private HotPathMetrics() {}
    private static final class Session {
        final long started = System.nanoTime();
        final LongAdder ticks=new LongAdder();
        final LongAdder automationDescriptors=new LongAdder();
        final LongAdder[] rows = counters(ActionType.values().length);
        final LongAdder[] families = counters(5);
        final LongAdder snapshots = new LongAdder(), snapshotNs = new LongAdder(), serverSnapshotNs = new LongAdder();
        final LongAdder waits = new LongAdder(), waitNs = new LongAdder(), serverWaitNs = new LongAdder();
    }
    private static LongAdder[] counters(int size) {
        LongAdder[] result = new LongAdder[size];
        for (int i=0;i<size;i++) result[i]=new LongAdder();
        return result;
    }
    public static void start() { session = new Session(); }
    public static void clear() { session=null;serverThread=null;lastReport="Замеры ещё не запускались: /log perf start"; }
    public static String stop() {
        Session current=session;
        if(current!=null) { session=null;lastReport=report(current); }
        return lastReport;
    }
    public static String report() { Session current=session;return current==null?lastReport:report(current); }
    public static void queued(ActionType type, String source) {
        Session current=session;if(current==null)return;
        if(type!=null)current.rows[type.ordinal()].increment();
        int family = type==ActionType.ME_PUT||type==ActionType.ME_TAKE
                || type==ActionType.ME_FILTER_CHANGE||type==ActionType.ME_UPGRADE_CHANGE
                || type==ActionType.ME_CELL_CHANGE||type==ActionType.ME_SETTING_CHANGE
                || type==ActionType.ME_CRAFT_REQUEST||type==ActionType.ME_CRAFT_CANCEL||type==ActionType.ME_CRAFT_FINISH ? 0
                : source!=null&&source.startsWith("create:bearing:")?1
                : source!=null&&source.startsWith("create:cart:")?2
                : source!=null&&(source.startsWith("create:")||source.equals("mod:create"))?3:4;
        current.families[family].increment();
    }
    public static long beginSnapshot() { return session==null?0:System.nanoTime(); }
    public static long snapshotCount() { var current=session;return current==null?0:current.snapshots.sum(); }
    public static void endSnapshot(long start) {
        Session current=session;if(current==null||start==0||start<current.started)return;
        long elapsed=System.nanoTime()-start;
        current.snapshots.increment();current.snapshotNs.add(elapsed);
        if(Thread.currentThread()==serverThread)current.serverSnapshotNs.add(elapsed);
    }
    public static void waited(long nanos) {
        Session current=session;if(current==null)return;
        current.waits.increment();current.waitNs.add(nanos);
        // Explicit server identity avoids mistaking CPU/event/disk workers for world ticks.
        if(Thread.currentThread()==serverThread)current.serverWaitNs.add(nanos);
    }
    private static volatile Thread serverThread;
    public static void serverThread(Thread thread) { serverThread=thread; }
    public static void automationDescriptor(){var current=session;if(current!=null)current.automationDescriptors.increment();}
    public static long automationDescriptors(){var current=session;return current==null?0:current.automationDescriptors.sum();}
    public static void tick(){var current=session;if(current!=null)current.ticks.increment();}
    private static String report(Session current) {
        double seconds=Math.max(.001,(System.nanoTime()-current.started)/1e9);
        long total=0;for(var counter:current.families)total+=counter.sum();
        var out=new StringBuilder(String.format(Locale.ROOT,"Период: %.1f с. Принято записей: %d (%.1f/с).\n",seconds,total,total/seconds));
        String[] names={"AE2","Create: подшипники","Create: вагонетки","Create: остальные","Другие события"};
        for(int i=0;i<names.length;i++)if(current.families[i].sum()>0)out.append(String.format(Locale.ROOT,"%s: %d (%.1f/с)\n",names[i],current.families[i].sum(),current.families[i].sum()/seconds));
        double wait=current.serverWaitNs.sum()/1e6;
        out.append(String.format(Locale.ROOT,"Снимки на серверном потоке: %.2f мс за период (%d вызовов; вложенные входят в сумму).\nОжидание очередей на серверном потоке: %.2f мс.\n",current.serverSnapshotNs.sum()/1e6,current.snapshots.sum(),wait));
        if(current.ticks.sum()>0)out.append(String.format(Locale.ROOT,"Ожидание очередей: %.3f мс/тик (%d тиков).\n",wait/current.ticks.sum(),current.ticks.sum()));
        out.append("Дескрипторов внешней автоматики Create: ").append(current.automationDescriptors.sum()).append(".\n");
        out.append("Это измеренные операции Logger; полный вклад в TPS проверяется профилировщиком сервера.");
        return out.toString();
    }
}
