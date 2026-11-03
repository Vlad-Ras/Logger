package com.roften.avilixlogger.core;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded handoff with backpressure, including payloads currently being processed. */
final class WeightedQueue<T> {
    enum Offer { ACCEPTED, FULL, BUSY, OVERSIZED }
    record Work<T>(T value, long bytes) {}
    record Statistics(String name,int queued,int outstanding,long bytes,long peakBytes,int peakOutstanding,
                      int countLimit,long byteLimit,long waits,int waitingProducers,long maxWaitNanos) {}
    private final ArrayDeque<Work<T>> entries = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final Condition space = lock.newCondition();
    private final int capacity;
    private final long maxBytes;
    private final String name;
    private long bytes;
    private long peakBytes,maxWaitNanos;
    private int peakOutstanding;
    private int outstanding;
    private long waits;
    private int waitingProducers;
    private int batchWaiters;
    private boolean closed;
    private boolean pressureRequested;

    WeightedQueue(int capacity, long maxBytes) {
        this("queue",capacity,maxBytes);
    }
    WeightedQueue(String name,int capacity, long maxBytes) {
        this.name=name;
        this.capacity = Math.max(1, capacity);
        this.maxBytes = Math.max(1, maxBytes);
    }

    void put(T value, long weight) {
        weight = Math.max(1, weight);
        lock.lock();
        try {
            // A single unusually large snapshot must still be logged. It runs alone.
            if (!closed && full(weight)) {
                waits++;
                waitingProducers++;
                // Only a batching consumer can free its own held partial batch. Wake it now.
                if (batchWaiters > 0) ready.signalAll();
                long started = System.nanoTime();
                try { while (!closed && full(weight)) space.awaitUninterruptibly(); }
                finally {
                    waitingProducers--;
                    long waited=System.nanoTime()-started;maxWaitNanos=Math.max(maxWaitNanos,waited);
                    HotPathMetrics.waited(waited);
                }
            }
            if (closed) throw new IllegalStateException("Logger queue is closed");
            entries.addLast(new Work<>(value, weight));
            bytes += weight;
            outstanding++;
            peakBytes=Math.max(peakBytes,bytes);peakOutstanding=Math.max(peakOutstanding,outstanding);
            ready.signal();
        } finally { lock.unlock(); }
    }

    String name(){return name;}
    /** No capacity wait on the server; the short queue lock has a separate finite budget. */
    Offer offer(T value,long weight) {
        return offer(value,weight,0,0);
    }
    /** Small lifecycle receipts can use a bounded reserve even while bulk capture is paused. */
    Offer offerReserved(T value,long weight,int reserveCount,long reserveBytes) {
        if(weight>reserveBytes)return Offer.OVERSIZED;
        return offer(value,weight,reserveCount,reserveBytes);
    }
    private Offer offer(T value,long weight,int reserveCount,long reserveBytes) {
        weight=Math.max(1,weight);boolean acquired;
        try{acquired=lock.tryLock(200,TimeUnit.MICROSECONDS);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return Offer.BUSY;}
        if(!acquired)return Offer.BUSY;
        try{
            if(closed)throw new IllegalStateException("Logger queue is closed");
            if(weight>maxBytes+reserveBytes && outstanding>0)return Offer.OVERSIZED;
            boolean full=reserveCount==0 ? full(weight)
                    : outstanding >= (long)capacity+reserveCount || bytes > maxBytes+reserveBytes-weight;
            if(full){pressureRequested=true;ready.signalAll();return Offer.FULL;}
            entries.addLast(new Work<>(value,weight));bytes+=weight;outstanding++;
            peakBytes=Math.max(peakBytes,bytes);peakOutstanding=Math.max(peakOutstanding,outstanding);ready.signal();return Offer.ACCEPTED;
        }finally{lock.unlock();}
    }
    private boolean full(long weight) {
        return outstanding >= capacity || (outstanding > 0 && (weight > maxBytes || bytes > maxBytes - weight));
    }

    Work<T> poll(long timeoutMillis) throws InterruptedException {
        return poll(timeoutMillis, false);
    }

    /** Only for a consumer already holding a partial batch: null requests an early flush. */
    Work<T> pollForBatch(long timeoutMillis) throws InterruptedException {
        return poll(timeoutMillis, true);
    }

    private Work<T> poll(long timeoutMillis, boolean flushOnPressure) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lockInterruptibly();
        try {
            while (entries.isEmpty() && !closed && remaining > 0 && (!flushOnPressure || waitingProducers == 0&&!pressureRequested)) {
                if (flushOnPressure) batchWaiters++;
                try { remaining = ready.awaitNanos(remaining); }
                finally { if (flushOnPressure) batchWaiters--; }
            }
            return entries.pollFirst();
        } finally { lock.unlock(); }
    }

    void complete(Work<T> work) {
        lock.lock();
        try {
            bytes -= work.bytes();
            outstanding--;
            pressureRequested=false;
            space.signalAll();
        } finally { lock.unlock(); }
    }

    boolean awaitEmpty(long deadlineNanos) throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (outstanding > 0) { long remaining = deadlineNanos - System.nanoTime(); if (remaining <= 0) return false; space.awaitNanos(remaining); }
            return true;
        } finally { lock.unlock(); }
    }

    void close() {
        lock.lock();
        try { closed = true; ready.signalAll(); space.signalAll(); }
        finally { lock.unlock(); }
    }

    boolean exhausted() {
        lock.lock();
        try { return closed && entries.isEmpty(); }
        finally { lock.unlock(); }
    }

    long retainedBytes() {
        lock.lock();
        try { return bytes; } finally { lock.unlock(); }
    }

    Statistics statistics() {
        lock.lock();
        try{return new Statistics(name,entries.size(),outstanding,bytes,peakBytes,peakOutstanding,capacity,maxBytes,waits,waitingProducers,maxWaitNanos);}
        finally{lock.unlock();}
    }
    void resetStatistics() {
        lock.lock();
        try{peakBytes=bytes;peakOutstanding=outstanding;waits=0;maxWaitNanos=0;}
        finally{lock.unlock();}
    }

    long backpressureCount() {
        lock.lock();
        try { return waits; } finally { lock.unlock(); }
    }

    boolean hasWaitingProducers() {
        lock.lock();
        try { return waitingProducers > 0; } finally { lock.unlock(); }
    }

    boolean hasPressure(){lock.lock();try{return waitingProducers>0||pressureRequested;}finally{lock.unlock();}}
    /** Shutdown must not discard work merely because a caller was interrupted. */
    static void join(Thread thread) {
        boolean interrupted = false;
        while (thread.isAlive()) {
            try { thread.join(); }
            catch (InterruptedException e) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
