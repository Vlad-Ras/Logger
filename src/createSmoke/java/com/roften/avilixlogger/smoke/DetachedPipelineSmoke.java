package com.roften.avilixlogger.smoke;

import com.roften.avilixlogger.core.AsyncLogStorage;
import com.roften.avilixlogger.core.LogEntry;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Replay already detached receipts off the world thread. Server overload has its own native fixture. */
final class DetachedPipelineSmoke {
    static void append(AsyncLogStorage storage,List<LogEntry> rows){
        var failure=new AtomicReference<Throwable>();
        var producer=new Thread(()->{try{rows.forEach(storage::append);}catch(Throwable error){failure.set(error);}},"native-detached-pipeline");
        producer.setDaemon(true);producer.start();
        try{producer.join(10_000);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new AssertionError("detached producer interrupted",interrupted);}
        if(producer.isAlive()||failure.get()!=null)throw new AssertionError("detached producer failed",failure.get());
    }
}
