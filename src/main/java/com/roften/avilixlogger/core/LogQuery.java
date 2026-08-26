package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;

/**
 * Query object for log search / rollback.
 */
public final class LogQuery {
    public String dim;
    public long sinceTs;
    public long untilTs;
    public int limit = 100;

    /** If set, filter by exact block position. */
    public BlockPos exactPos;

    /** If set, filter by axis-aligned bounding box (inclusive). */
    public BlockPos minPos;
    public BlockPos maxPos;

    /** Optional player name filter (case-insensitive exact match). */
    public String actorName;

    /** Optional owner filter (case-insensitive). Used primarily for plane log searches. */
    public String owner;

    /** Optional block id/name filter. Storage may push this down; GUI still post-filters as a safety net. */
    public String blockIdFilter;

    /** Optional plane name/type filter. Storage may push this down; GUI still post-filters as a safety net. */
    public String planeNameFilter;

    /** Optional text marker filter for compat logs such as trains/cannons. */
    public String extraTextFilter;

    /** Optional source label for diagnostics (chat/gui/rollback). */
    public String debugSource;

    /** Optional action type filter. */
    public ActionType type;

    /**
     * Optional action type filter set (preferred over {@link #type}).
     * If set, the storage layer may translate it into an SQL IN(...) filter.
     */
    public java.util.EnumSet<ActionType> types;

    /**
     * When true, storage must read from heavy/detail tables instead of the lightweight feed.
     * Rollback and GUI detail panes need full snapshots; ordinary list pages do not.
     */
    public boolean requireDetails;

    /** Internal paging: id cursor (exclusive). */
    public long afterId;
    public long beforeId;

    public LogQuery() {
        this.untilTs = System.currentTimeMillis();
    }

    public LogQuery copy() {
        LogQuery q = new LogQuery();
        q.dim = this.dim;
        q.sinceTs = this.sinceTs;
        q.untilTs = this.untilTs;
        q.limit = this.limit;
        q.exactPos = this.exactPos;
        q.minPos = this.minPos;
        q.maxPos = this.maxPos;
        q.actorName = this.actorName;
        q.owner = this.owner;
        q.blockIdFilter = this.blockIdFilter;
        q.planeNameFilter = this.planeNameFilter;
        q.extraTextFilter = this.extraTextFilter;
        q.debugSource = this.debugSource;
        q.type = this.type;
        q.types = this.types != null ? this.types.clone() : null;
        q.requireDetails = this.requireDetails;
        q.afterId = this.afterId;
        q.beforeId = this.beforeId;
        return q;
    }
}
