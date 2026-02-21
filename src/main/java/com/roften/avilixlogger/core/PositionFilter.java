package com.roften.avilixlogger.core;

/**
 * Simple point-in-region test used to precisely filter log entries after a broad storage query.
 *
 * This keeps the SQL simple (we can query by a bounding box) while still applying an exact
 * shape constraint in Java (e.g. WorldEdit non-cuboid selections).
 */
@FunctionalInterface
public interface PositionFilter {
    boolean contains(int x, int y, int z);
}
