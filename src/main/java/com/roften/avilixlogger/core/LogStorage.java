package com.roften.avilixlogger.core;

import java.util.List;

/**
 * Storage abstraction.
 *
 * IMPORTANT: this mod is designed for high write throughput.
 * Implementations must not perform blocking I/O on the server tick thread.
 */
public interface LogStorage {

    /** Hand off a log entry; the runtime queues it in memory, applying backpressure only on saturation. */
    void append(LogEntry entry);

    /**
     * Query entries in chronological order.
     *
     * @param q query
     * @return at most q.limit entries
     */
    List<LogEntry> query(LogQuery q);

    /**
     * Query entries for rollback in reverse chronological order.
     * Implementations should push down the filters to the storage engine.
     */
    List<LogEntry> queryReverse(LogQuery q);

    /** Lightweight owner metadata; never infer ownership from an event actor. Called on workers only. */
    default java.util.Map<java.util.UUID,String> cartOwners(java.util.Set<java.util.UUID> ids) {
        return readCartOwners(this,ids);
    }
    static java.util.Map<java.util.UUID,String> readCartOwners(LogStorage storage,java.util.Set<java.util.UUID> ids) {
        if(ids.isEmpty())return java.util.Map.of();
        var q=new LogQuery();q.dim="*";q.sinceTs=0;q.untilTs=Long.MAX_VALUE;q.cartIds=ids;q.sourcePrefix="create:cart:";q.types=com.roften.avilixlogger.net.CartHistoryQuery.checkpoints();q.limit=4096;
        var out=new java.util.HashMap<java.util.UUID,String>();
        for(var e:storage.queryReverse(q)){var id=CartAuditContext.id(e.source);String owner=CartOwnerMetadata.owner(e.extra);if(id!=null&&!owner.isBlank())out.putIfAbsent(id,owner);}
        return java.util.Map.copyOf(out);
    }

    /** Called only on a worker before a destructive query; wait for accepted rows to become visible. */
    default boolean awaitVisible(long deadlineNanos) throws InterruptedException { return true; }

    /** Flush and shutdown background writers. */
    void shutdown();
}
