package com.roften.avilixlogger.core;

import net.minecraft.world.level.Level;

import com.roften.avilixlogger.AvilixLoggerMod;
import com.roften.avilixlogger.LoggerConfig;
import com.roften.avilixlogger.api.LogAdapterRegistry;

/**
 * Lazy-initialized runtime context.
 */
public final class LoggerRuntime {
    private static final NoopLogStorage UNAUTHORIZED = new NoopLogStorage();
    private static volatile PendingLogStorage PENDING;
    private static volatile DeduplicatingLogStorage FRONT;

    private static volatile AsyncLogStorage ASYNC;
    private static volatile LogStorage STORAGE;
    private static volatile boolean INIT_STARTED;
    private static volatile long INIT_GENERATION;
    private static volatile Thread INIT_THREAD;

    private LoggerRuntime() {}

    /** Inspect existing queues only. Reading diagnostics must not start a logger or storage connection. */
    public static String captureQueueReport() {
        var states=new java.util.ArrayList<WeightedQueue.Statistics>(3);
        var cpu=AsyncLogProcessor.queueStatistics();if(cpu!=null)states.add(cpu);
        var events=ASYNC;if(events!=null)states.add(events.queueStatistics());
        var journal=PENDING;if(journal!=null)states.add(journal.queueStatistics());
        var storage = STORAGE;
        var text = new StringBuilder(ServerCaptureGuard.report()).append('\n');
        if (storage instanceof HealthAwareLogStorage health) {
            text.append(health.storageName()).append(health.isLikelyAvailable()
                    ? " · готов принимать записи по данным драйвера."
                    : " · временно недоступен.");
        } else {
            text.append(storage == null ? "ClickHouse ещё не подключён." : "Хранилище подключено.");
        }
        text.append('\n');
        if (states.isEmpty()) return text.append("Очереди Logger ещё не запущены.").toString();
        text.append("Буферы Logger:\n");
        for(var state:states) {
            String name=switch(state.name()){case "cpu"->"Обработка снимков";case "events"->"События";case "journal"->"Запись на диск";default->state.name();};
            text.append(String.format(java.util.Locale.ROOT,"%s: занято %d из %d; буфер %.1f из %.1f МБ (пик %.1f МБ).\n",
                name,state.outstanding(),state.countLimit(),state.bytes()/1048576.0,state.byteLimit()/1048576.0,state.peakBytes()/1048576.0));
        }
        return text.toString();
    }

    public static String queueReport() {
        var text = new StringBuilder(captureQueueReport());
        var runtime=Runtime.getRuntime();
        text.append(String.format(java.util.Locale.ROOT,"Память всей JVM: %.0f / %.0f МБ. Буферы выше относятся к Logger; отдельно память его объектов здесь не измеряется.",(runtime.totalMemory()-runtime.freeMemory())/1048576.0,runtime.maxMemory()/1048576.0));
        return text.append("\n").append(PlayerDeathAudit.report()).toString();
    }
    public static void resetQueueStatistics() {
        AsyncLogProcessor.resetQueueStatistics();
        var events=ASYNC;if(events!=null)events.resetQueueStatistics();
        var journal=PENDING;if(journal!=null)journal.resetQueueStatistics();
    }

    public static String resumeCapture(){
        if(!com.roften.avilixlogger.auth.AuthCoreBridge.isAuthorized())return "AuthCore не разрешил работу Logger.";
        if(!LoggerConfig.isEnabled())return "Logger отключён в основной конфигурации.";
        if(!ServerCaptureGuard.paused())return "Аварийной остановки захвата нет.";
        var cpu=AsyncLogProcessor.queueStatistics();var events=ASYNC;var journal=PENDING;
        if(cpu!=null&&cpu.outstanding()!=0||events!=null&&events.queueStatistics().outstanding()!=0||journal!=null&&journal.queueStatistics().outstanding()!=0)
            return "Принятые записи ещё обрабатываются. Сначала освободите очереди; состояние видно в отчёте.";
        var gap=ServerCaptureGuard.prepareResume();if(gap==null)return "Аварийной остановки захвата нет.";
        if(events==null||!events.appendDiagnostic(captureGapEntry(gap)))return "Запись интервала пропуска пока не принята. Захват остаётся остановленным; повторите после освобождения очередей.";
        ServerCaptureGuard.resume(gap);
        java.util.concurrent.CompletableFuture.runAsync(()->AvilixLoggerMod.LOGGER.warn("[AvilixLogger] {}",ServerCaptureGuard.report()));
        return "Захват возобновлён. Интервал пропуска будет сохранён отдельной записью журнала.\n"+ServerCaptureGuard.report();
    }
    public static boolean enqueueDiagnostic(LogEntry entry){
        if(entry==null||entry.type!=ActionType.LOGGER_DIAGNOSTIC||!"logger:diagnostic".equals(entry.source)||!LoggerConfig.isEnabled())return false;
        var events=ASYNC;if(events==null)return false;LogIdGenerator.ensure(entry);return events.appendDiagnostic(entry);
    }
    public static void autoResumeCapture() {
        var gap=ServerCaptureGuard.active();
        if(gap!=null && System.currentTimeMillis()-gap.since()>=1000) resumeCapture();
    }
    static LogEntry captureGapEntry(ServerCaptureGuard.Gap gap){
        var row=new LogEntry();row.ts=gap.until();row.type=ActionType.CAPTURE_GAP;row.dim="*";row.actorName="Система";row.source="logger:capture-gap";
        row.extra="Пропуск захвата "+java.time.Instant.ofEpochMilli(gap.since())+" — "+java.time.Instant.ofEpochMilli(gap.until())+"; очередь "+gap.queue()+"; отклонено заданий "+gap.rejected()+"; пропущено проверок "+gap.suppressedChecks()+" (не число событий)";
        var tag=new net.minecraft.nbt.CompoundTag();tag.putString("Kind","LoggerCaptureGap");tag.putBoolean("PositionKnown",false);tag.putLong("Since",gap.since());tag.putLong("Until",gap.until());
        tag.putString("Queue",gap.queue());tag.putString("Reason",gap.reason());tag.putLong("RejectedSubmissions",gap.rejected());tag.putLong("SuppressedCaptureChecks",gap.suppressedChecks());row.beAfter=tag.toString();return row;
    }
    public static LogStorage storage(Level level) {
        if (!LoggerConfig.isEnabled()) return UNAUTHORIZED;
        LogStorage events = eventStorage();
        if (STORAGE == null) startAsyncInit();
        return events;
    }

    /** Non-blocking lookup of the existing connection. Client report SQL runs on its own worker. */
    public static ClickHouseLogStorage clientReportStorage() {
        return STORAGE instanceof ClickHouseLogStorage clickHouse ? clickHouse : null;
    }

    public static void warmupAsync() {
        if (!LoggerConfig.isEnabled()) return;
        eventStorage();
        AsyncLogProcessor.warmup();
        startAsyncInit();
    }

    private static AsyncLogStorage eventStorage() {
        AsyncLogStorage current = ASYNC;
        if (current != null) return current;
        synchronized (LoggerRuntime.class) {
            if (ASYNC == null) {
                PENDING = new PendingLogStorage();
                FRONT = new DeduplicatingLogStorage(PENDING);
                ASYNC = new AsyncLogStorage(FRONT,
                    LoggerConfig.VALUES.asyncQueueCapacity.get(),
                    LoggerConfig.captureQueueBytes());
            }
            return ASYNC;
        }
    }

    private static void startAsyncInit() {
        if (INIT_STARTED) return;
        if (!LoggerConfig.isEnabled()) return;
        synchronized (LoggerRuntime.class) {
            if (INIT_STARTED) return;
            INIT_STARTED = true;
            long generation = ++INIT_GENERATION;

            Thread t = new Thread(() -> {
                int attempt = 0;
                try {
                    LogAdapterRegistry.discover();
                    while (INIT_STARTED && INIT_GENERATION == generation && LoggerConfig.isEnabled()) {
                        attempt++;
                        BatchLogStorage real;
                        try {
                            real = createConfiguredStorage();
                        } catch (Throwable failure) {
                            if (attempt == 1 || attempt % 12 == 0) {
                                AvilixLoggerMod.LOGGER.error(
                                        "[AvilixLogger] ClickHouse initialization failed (attempt {}). "
                                                + "Logging is queued and connection will retry automatically.",
                                        attempt,
                                        failure
                                );
                            } else {
                                AvilixLoggerMod.LOGGER.warn(
                                        "[AvilixLogger] ClickHouse is still unavailable (attempt {}): {}",
                                        attempt,
                                        rootMessage(failure)
                                );
                            }
                            if (!sleepBeforeRetry(generation)) return;
                            continue;
                        }

                        synchronized (LoggerRuntime.class) {
                            if (!INIT_STARTED || INIT_GENERATION != generation || !LoggerConfig.isEnabled()) {
                                real.shutdown();
                                return;
                            }
                            PENDING.setDelegate(real);
                            STORAGE = real;
                        }
                        if (attempt > 1) {
                            AvilixLoggerMod.LOGGER.info(
                                    "[AvilixLogger] ClickHouse connection recovered after {} attempts; queued logs are being flushed.",
                                    attempt
                            );
                        }
                        return;
                    }
                } catch (Throwable fatal) {
                    AvilixLoggerMod.LOGGER.error("[AvilixLogger] Storage initializer stopped unexpectedly.", fatal);
                } finally {
                    if (INIT_GENERATION == generation && STORAGE == null && !LoggerConfig.isEnabled()) {
                        INIT_STARTED = false;
                    }
                    if (INIT_THREAD == Thread.currentThread()) INIT_THREAD = null;
                }
            }, "avilixlogger-storage-init");
            t.setDaemon(true);
            INIT_THREAD = t;
            t.start();
        }
    }

    private static boolean sleepBeforeRetry(long generation) {
        long delayMs;
        try {
            delayMs = Math.max(1_000L, LoggerConfig.VALUES.clickHouseHealthCooldownMs.get());
        } catch (Throwable ignored) {
            delayMs = 10_000L;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return INIT_STARTED && INIT_GENERATION == generation && !Thread.currentThread().isInterrupted();
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current != null && current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current == null ? null : current.getMessage();
        return message == null || message.isBlank()
                ? String.valueOf(current == null ? error : current)
                : message;
    }

    public static boolean isInitializing() {
        return LoggerConfig.isEnabled() && INIT_STARTED && STORAGE == null;
    }

    private static BatchLogStorage createConfiguredStorage() {
        AvilixLoggerMod.LOGGER.info("[AvilixLogger] Starting ClickHouse storage (MySQL is not read or migrated)");
        return new ClickHouseLogStorage();
    }

    public static void shutdown() {
        Thread initThread;
        synchronized (LoggerRuntime.class) {
            INIT_STARTED = false;
            INIT_GENERATION++;
            initThread = INIT_THREAD;
        }
        if (initThread != null) initThread.interrupt();
        AsyncLogProcessor.shutdown();
        AsyncLogStorage events = ASYNC;
        if (events != null) events.shutdown();
        PendingLogStorage pending = PENDING;
        if (pending != null) pending.shutdown();
        ChatAuditLogger.clearPending();
        AdaptiveLogDiagnostics.logSummary();
        LogStorage storage = STORAGE;
        if (storage != null) storage.shutdown();
        if (initThread != null) WeightedQueue.join(initThread);
        synchronized (LoggerRuntime.class) {
            ASYNC = null;
            STORAGE = null;
            PENDING = null;
            if (FRONT != null) FRONT.clear();
            FRONT = null;
            INIT_THREAD = null;
        }
    }
}
