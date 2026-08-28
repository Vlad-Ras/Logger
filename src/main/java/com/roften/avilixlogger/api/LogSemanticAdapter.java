package com.roften.avilixlogger.api;

import com.roften.avilixlogger.core.LogEntry;

/**
 * Optional extension point for mods that want to add domain-specific meaning to a log row.
 *
 * <p>The core logger already captures ordinary block, entity and menu mutations without an
 * adapter. Adapters are only needed when a mod keeps important meaning outside the normal
 * Minecraft state (for example, a machine name or a virtual owner). Implementations may be
 * registered directly through {@link LogAdapterRegistry#register(LogSemanticAdapter)} or
 * exposed through {@code META-INF/services/com.roften.avilixlogger.api.LogSemanticAdapter}.</p>
 *
 * <p>Adapters run before deduplication and persistence. They must be fast, must not query or
 * modify the world, and must not perform blocking I/O.</p>
 */
public interface LogSemanticAdapter {
    /** Stable identifier used in diagnostics. */
    String id();

    /** Higher-priority adapters run first. */
    default int priority() {
        return 0;
    }

    /** Cheap predicate that decides whether this adapter understands the row. */
    boolean supports(LogEntry entry);

    /** Enrich source/extra metadata without removing rollback snapshots. */
    void enrich(LogEntry entry);
}
