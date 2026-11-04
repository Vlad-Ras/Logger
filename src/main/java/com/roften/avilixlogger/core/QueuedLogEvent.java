package com.roften.avilixlogger.core;

/** Detached input resolved by the ordered event worker; null means a verified no-op. */
interface QueuedLogEvent {
    LogEntry resolve();
}
