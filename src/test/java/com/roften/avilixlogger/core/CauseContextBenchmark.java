package com.roften.avilixlogger.core;

import java.lang.management.ManagementFactory;
/** Comparative microbenchmark only; not an estimate of server MSPT. */
public final class CauseContextBenchmark {
    private static volatile Object consume;
    private static void run(int n){for(int i=0;i<n;i++)try(var scope=CauseContext.unattributed()){consume=CauseContext.peek();}}
    public static void main(String[] args){
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();long id=Thread.currentThread().threadId();
        run(1000000);
        for(int round=0;round<5;round++){
            long bytes=bean.getThreadAllocatedBytes(id),time=System.nanoTime();run(2000000);
            long elapsed=System.nanoTime()-time,allocated=bean.getThreadAllocatedBytes(id)-bytes;
            System.out.printf("round=%d ns/op=%.2f bytes/op=%.2f%n",round,elapsed/2000000.0,allocated/2000000.0);
        }
    }
}
