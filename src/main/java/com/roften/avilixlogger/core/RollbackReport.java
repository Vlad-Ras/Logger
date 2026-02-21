package com.roften.avilixlogger.core;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregated rollback statistics for a single rollback execution.
 */
public final class RollbackReport {

    public int processed;
    public int applied;
    public int skipped;

    /** Applied counts by action type. */
    public final EnumMap<ActionType, Integer> appliedByType = new EnumMap<>(ActionType.class);

    /** Skipped counts by reason (human-readable key). */
    public final Map<String, Integer> skippedReasons = new HashMap<>();

    // High-level buckets
    public int blocksRestored;
    public int blockEntitiesRestored;
    public int containersRestored;
    public int entitiesRespawned;
    public int entitiesRemoved;
    public int itemsGivenOrSpawned;
    public int itemsRemovedFromInventory;

    public void onApplied(ActionType type) {
        applied++;
        processed++;
        appliedByType.merge(type, 1, Integer::sum);
    }

    public void onSkipped(String reason) {
        skipped++;
        processed++;
        if (reason == null || reason.isBlank()) reason = "unknown";
        skippedReasons.merge(reason, 1, Integer::sum);
    }
}
