package com.roften.avilixlogger.core;

import java.util.ArrayDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Bounded handoff with backpressure, including payloads currently being processed. */
final class WeightedQueue<T> {
    record Work<T>(T value, long bytes) {}
    private final ArrayDeque<Work<T>> entries = new ArrayDeque<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition ready = lock.newCondition();
    private final Condition space = lock.newCondition();
    private final int capacity;
    private final long maxBytes;
    private long bytes;
    private int outstanding;
    private long waits;
    private boolean closed;

    WeightedQueue(int capacity, long maxBytes) {
        this.capacity = Math.max(1, capacity);
        this.maxBytes = Math.max(1, maxBytes);
    }

    void put(T value, long weight) {
        weight = Math.max(1, weight);
        lock.lock();
        try {
            // A single unusually large snapshot must still be logged. It runs alone.
            if (!closed && full(weight)) waits++;
            while (!closed && full(weight)) space.awaitUninterruptibly();
            if (closed) throw new IllegalStateException("Logger queue is closed");
            entries.addLast(new Work<>(value, weight));
            bytes += weight;
            outstanding++;
            ready.signal();
        } finally { lock.unlock(); }
    }

    private boolean full(long weight) {
        return outstanding >= capacity || (outstanding > 0 && (weight > maxBytes || bytes > maxBytes - weight));
    }

    Work<T> poll(long timeoutMillis) throws InterruptedException {
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        lock.lockInterruptibly();
        try {
            while (entries.isEmpty() && !closed && remaining > 0) remaining = ready.awaitNanos(remaining);
            return entries.pollFirst();
        } finally { lock.unlock(); }
    }

    void complete(Work<T> work) {
        lock.lock();
        try {
            bytes -= work.bytes();
            outstanding--;
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

    long backpressureCount() {
        lock.lock();
        try { return waits; } finally { lock.unlock(); }
    }

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
