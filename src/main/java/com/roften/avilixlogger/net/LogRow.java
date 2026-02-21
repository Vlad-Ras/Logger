package com.roften.avilixlogger.net;

import net.minecraft.network.chat.Component;

/**
 * One row shown in the optional client GUI.
 *
 * <p>GUI may aggregate multiple raw log entries into a single row to avoid spam.
 * In that case {@code aggregated=true} and {@code rawIds} contains the underlying SQL ids.
 */
public record LogRow(long id,
                     String dim,
                     int x,
                     int y,
                     int z,
                     Component line,
                     boolean aggregated,
                     long[] rawIds) {

    public static LogRow single(long id, String dim, int x, int y, int z, Component line) {
        return new LogRow(id, dim, x, y, z, line, false, new long[]{id});
    }
}
