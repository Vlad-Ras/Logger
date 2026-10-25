package com.roften.avilixlogger.core;

import java.util.ArrayList;

public final class DiagnosticCountersTest {
    public static void main(String[] args) throws Exception { run(); }
    static void run() throws Exception {
        var counters = new DiagnosticCounters(32);
        var threads = new ArrayList<Thread>();
        for (int t=0; t<8; t++) {
            final int worker=t;
            threads.add(new Thread(() -> {
                for (int i=0; i<10_000; i++) counters.increment(worker+"/"+i);
            }));
        }
        threads.forEach(Thread::start);
        for (var thread:threads) thread.join();
        if (counters.size()!=32 || counters.total()!=80_000 || !counters.top(12).contains("[other]="))
            throw new AssertionError("Concurrent diagnostic overflow is unbounded or loses counts");
        var longNames=new DiagnosticCounters(32);longNames.increment("x".repeat(4096));
        if (longNames.size()!=0 || longNames.total()!=1) throw new AssertionError("Oversized diagnostic key retained");
        System.out.println("DIAGNOSTIC_CARDINALITY_OK: 80000 unique sources, 8 producers, bounded retained keys and lossless counters");
    }
}
