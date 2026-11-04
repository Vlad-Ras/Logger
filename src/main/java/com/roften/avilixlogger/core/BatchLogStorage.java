package com.roften.avilixlogger.core;

import java.util.List;

/** A successful return acknowledges the entire durable batch; errors must propagate. */
interface BatchLogStorage extends LogStorage {
    void appendBatch(String token, List<LogEntry> entries) throws Exception;
}
