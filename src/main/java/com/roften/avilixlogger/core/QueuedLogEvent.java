package com.roften.avilixlogger.core;

/** Detached input resolved into a log row by the event worker. */
interface QueuedLogEvent {
    LogEntry resolve();
}
