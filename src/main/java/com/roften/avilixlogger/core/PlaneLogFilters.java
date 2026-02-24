package com.roften.avilixlogger.core;

import java.util.Locale;

/**
 * Plane-specific filtering helpers.
 *
 * Owner information is stored by compat layer into:
 * - entry.extra JSON (preferred)
 * - entityNbt / itemStackNbt (fallback)
 */
public final class PlaneLogFilters {

    private PlaneLogFilters() {}

    public static boolean matchesOwner(LogEntry e, String ownerNeedle) {
        if (e == null) return false;
        if (ownerNeedle == null || ownerNeedle.isBlank()) return true;

        String needle = ownerNeedle.trim().toLowerCase(Locale.ROOT);

        // 1) Preferred: extra JSON fields
        if (containsIgnoreCase(e.extra, "\"ownerName\":\"") || containsIgnoreCase(e.extra, "\"ownerUuid\":\"")) {
            if (containsIgnoreCase(e.extra, needle)) return true;
        }

        // 2) Fallback: SNBT blobs
        if (containsIgnoreCase(e.entityNbt, needle)) return true;
        if (containsIgnoreCase(e.itemStackNbt, needle)) return true;

        // 3) Fallback: some mods store it as plain keys without JSON
        if (containsIgnoreCase(e.extra, needle)) return true;

        return false;
    }

    public static boolean matchesPlaneName(LogEntry e, String planeNeedle) {
        if (e == null) return false;
        if (planeNeedle == null || planeNeedle.isBlank()) return true;

        String needle = planeNeedle.trim().toLowerCase(Locale.ROOT);

        // Preferred: extra JSON planeName/customName/planeId
        if (containsIgnoreCase(e.extra, "\"planeName\":\"") || containsIgnoreCase(e.extra, "\"customName\":\"") || containsIgnoreCase(e.extra, "\"planeId\":\"")) {
            if (containsIgnoreCase(e.extra, needle)) return true;
        }

        // entityType is usually a stable id (modid:entity)
        if (containsIgnoreCase(e.entityType, needle)) return true;

        // Fallback: SNBT blobs and raw extra
        if (containsIgnoreCase(e.entityNbt, needle)) return true;
        if (containsIgnoreCase(e.itemStackNbt, needle)) return true;
        if (containsIgnoreCase(e.extra, needle)) return true;

        return false;
    }

    private static boolean containsIgnoreCase(String haystack, String needleLower) {
        if (haystack == null || haystack.isBlank()) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needleLower);
    }
}
