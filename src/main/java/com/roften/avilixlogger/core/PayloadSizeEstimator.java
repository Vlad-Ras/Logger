package com.roften.avilixlogger.core;

/** Cheap conservative heap-weight estimates used for queue backpressure. */
public final class PayloadSizeEstimator {
    private static final long ENTRY_OVERHEAD = 256L;

    private PayloadSizeEstimator() {}

    public static long estimate(LogEntry entry) {
        if (entry == null) return 0L;
        long bytes = ENTRY_OVERHEAD;
        bytes += stringBytes(entry.dim);
        bytes += stringBytes(entry.actorName);
        bytes += stringBytes(entry.source);
        bytes += stringBytes(entry.blockBefore);
        bytes += stringBytes(entry.beBefore);
        bytes += stringBytes(entry.blockAfter);
        bytes += stringBytes(entry.beAfter);
        bytes += stringBytes(entry.entityType);
        bytes += stringBytes(entry.entityNbt);
        bytes += stringBytes(entry.itemStackNbt);
        bytes += stringBytes(entry.playerInvBefore);
        bytes += stringBytes(entry.playerInvAfter);
        bytes += stringBytes(entry.containerSlotsBefore);
        bytes += stringBytes(entry.containerSlotsAfter);
        bytes += stringBytes(entry.extra);
        return Math.max(ENTRY_OVERHEAD, bytes);
    }

    public static long estimateStrings(String... values) {
        long bytes = 128L;
        if (values != null) {
            for (String value : values) bytes += stringBytes(value);
        }
        return Math.max(128L, bytes);
    }

    private static long stringBytes(String value) {
        return value == null ? 0L : 40L + (long) value.length() * 2L;
    }
}
