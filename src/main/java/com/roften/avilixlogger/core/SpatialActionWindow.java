package com.roften.avilixlogger.core;

import net.minecraft.core.BlockPos;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.function.Function;

/** Bounded newest-first history with a conservative spatial rejection before scoring. */
final class SpatialActionWindow<T> implements Iterable<T> {
    private final ArrayDeque<T> entries = new ArrayDeque<>();
    private final int capacity;
    private final Function<T, BlockPos> position;
    private int additions;
    private int minX, minY, minZ, maxX, maxY, maxZ;

    SpatialActionWindow(int capacity, Function<T, BlockPos> position) {
        this.capacity = Math.max(1, capacity);
        this.position = position;
    }

    void add(T entry) {
        boolean first = entries.isEmpty();
        entries.addFirst(entry);
        if (entries.size() > capacity) entries.removeLast();
        if (first || ++additions >= capacity) {
            resetBounds(position.apply(entry));
            for (T retained : entries) expand(position.apply(retained));
            additions = 0;
        } else {
            // Evicted points may widen the box until the next rebuild, never exclude a match.
            // Rebuilding only once per capacity additions keeps insertion amortized O(1).
            expand(position.apply(entry));
        }
    }

    boolean mayContain(BlockPos target, double radiusSquared) {
        if (entries.isEmpty()) return false;
        double dx = Math.max(0.0, Math.max((double) minX - target.getX(), (double) target.getX() - maxX));
        double dy = Math.max(0.0, Math.max((double) minY - target.getY(), (double) target.getY() - maxY));
        double dz = Math.max(0.0, Math.max((double) minZ - target.getZ(), (double) target.getZ() - maxZ));
        return dx * dx + dy * dy + dz * dz <= radiusSquared;
    }

    int size() { return entries.size(); }
    @Override public Iterator<T> iterator() { return entries.iterator(); }

    private void resetBounds(BlockPos pos) {
        minX = maxX = pos.getX(); minY = maxY = pos.getY(); minZ = maxZ = pos.getZ();
    }

    private void expand(BlockPos pos) {
        minX = Math.min(minX, pos.getX()); maxX = Math.max(maxX, pos.getX());
        minY = Math.min(minY, pos.getY()); maxY = Math.max(maxY, pos.getY());
        minZ = Math.min(minZ, pos.getZ()); maxZ = Math.max(maxZ, pos.getZ());
    }
}
